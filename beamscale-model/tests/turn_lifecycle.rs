use std::collections::HashMap;

#[derive(Debug, Clone, PartialEq, Eq)]
struct Handle {
    object_key: String,
    request_id: String,
    owner_epoch: u64,
    sequence: u64,
}

#[derive(Debug, Clone)]
struct ActiveTurn {
    handle: Handle,
    caller: String,
    deadline_tick: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum BeginError {
    Busy,
    OwnershipLost,
}

#[derive(Debug)]
struct TurnLifecycleModel {
    owner_epoch: u64,
    sequence: u64,
    ownership_lost: bool,
    active: HashMap<String, ActiveTurn>,
}

impl TurnLifecycleModel {
    fn new(owner_epoch: u64) -> Self {
        Self {
            owner_epoch,
            sequence: 0,
            ownership_lost: false,
            active: HashMap::new(),
        }
    }

    fn begin(
        &mut self,
        object_key: &str,
        request_id: &str,
        caller: &str,
        now_tick: u64,
        ttl: u64,
    ) -> Result<Handle, BeginError> {
        self.expire(now_tick);
        if self.ownership_lost {
            return Err(BeginError::OwnershipLost);
        }
        if let Some(existing) = self.active.get_mut(object_key) {
            if existing.handle.request_id == request_id {
                existing.caller = caller.to_owned();
                return Ok(existing.handle.clone());
            }
            return Err(BeginError::Busy);
        }

        self.sequence += 1;
        let handle = Handle {
            object_key: object_key.to_owned(),
            request_id: request_id.to_owned(),
            owner_epoch: self.owner_epoch,
            sequence: self.sequence,
        };
        self.active.insert(
            object_key.to_owned(),
            ActiveTurn {
                handle: handle.clone(),
                caller: caller.to_owned(),
                deadline_tick: now_tick.saturating_add(ttl),
            },
        );
        Ok(handle)
    }

    fn caller_died(&mut self, caller: &str) {
        self.active.retain(|_, turn| turn.caller != caller);
    }

    fn expire(&mut self, now_tick: u64) {
        self.active.retain(|_, turn| turn.deadline_tick > now_tick);
    }

    fn complete(&mut self, handle: &Handle) -> bool {
        match self.active.get(&handle.object_key) {
            Some(active) if active.handle == *handle => {
                self.active.remove(&handle.object_key);
                true
            }
            _ => false,
        }
    }

    fn lose_ownership(&mut self, next_epoch: u64) {
        assert!(next_epoch > self.owner_epoch);
        self.owner_epoch = next_epoch;
        self.ownership_lost = true;
        self.active.clear();
    }

    fn active_count(&self) -> usize {
        self.active.len()
    }
}

#[test]
fn abandoned_live_caller_is_released_at_fixed_deadline() {
    let mut model = TurnLifecycleModel::new(7);
    let old = model
        .begin("cart/42", "request-old", "caller-a", 100, 20)
        .expect("first turn");
    assert_eq!(model.active_count(), 1);
    model.expire(120);
    assert_eq!(model.active_count(), 0);
    assert!(!model.complete(&old));
    assert!(model
        .begin("cart/42", "request-new", "caller-b", 120, 20)
        .is_ok());
}

#[test]
fn caller_death_releases_only_that_callers_turns() {
    let mut model = TurnLifecycleModel::new(7);
    model.begin("a", "r1", "caller-a", 100, 20).expect("a turn");
    let b = model.begin("b", "r2", "caller-b", 100, 20).expect("b turn");
    model.caller_died("caller-a");
    assert_eq!(model.active_count(), 1);
    assert!(model.complete(&b));
}

#[test]
fn duplicate_begin_rebinds_liveness_without_extending_deadline() {
    let mut model = TurnLifecycleModel::new(7);
    let first = model
        .begin("cart/42", "same-request", "caller-a", 100, 20)
        .expect("first turn");
    let retry = model
        .begin("cart/42", "same-request", "caller-b", 110, 999)
        .expect("retry");
    assert_eq!(first, retry);

    model.caller_died("caller-a");
    assert_eq!(model.active_count(), 1);

    model.expire(120);
    assert_eq!(model.active_count(), 0);
}

#[test]
fn ownership_loss_clears_turns_and_old_actor_cannot_self_reclaim() {
    let mut model = TurnLifecycleModel::new(7);
    model
        .begin("a", "r1", "caller-a", 100, 20)
        .expect("old turn");
    model.lose_ownership(8);
    assert_eq!(model.active_count(), 0);
    assert_eq!(
        model.begin("a", "r2", "caller-a", 101, 20),
        Err(BeginError::OwnershipLost)
    );
}
