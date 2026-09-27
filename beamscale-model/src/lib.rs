use std::collections::HashMap;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ModelError {
    Busy,
    StaleEpoch,
    InvalidHandle,
    NotHolder,
    StaleFencingToken,
    StaleTerm,
    NotCommitted,
    GenerationStillPinned,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TurnHandle {
    pub object_key: String,
    pub request_id: String,
    pub owner_epoch: u64,
}

#[derive(Debug, Default)]
pub struct DurableActorModel {
    owner_epoch: u64,
    active: HashMap<String, TurnHandle>,
}

impl DurableActorModel {
    pub fn new(owner_epoch: u64) -> Self {
        Self {
            owner_epoch,
            active: HashMap::new(),
        }
    }

    pub fn begin_turn(
        &mut self,
        object_key: &str,
        request_id: &str,
    ) -> Result<TurnHandle, ModelError> {
        if let Some(existing) = self.active.get(object_key) {
            if existing.request_id == request_id && existing.owner_epoch == self.owner_epoch {
                return Ok(existing.clone());
            }
            return Err(ModelError::Busy);
        }

        let handle = TurnHandle {
            object_key: object_key.to_owned(),
            request_id: request_id.to_owned(),
            owner_epoch: self.owner_epoch,
        };
        self.active.insert(object_key.to_owned(), handle.clone());
        Ok(handle)
    }

    pub fn validate(&self, handle: &TurnHandle) -> Result<(), ModelError> {
        if handle.owner_epoch != self.owner_epoch {
            return Err(ModelError::StaleEpoch);
        }
        match self.active.get(&handle.object_key) {
            Some(active) if active == handle => Ok(()),
            _ => Err(ModelError::InvalidHandle),
        }
    }

    pub fn complete_turn(&mut self, handle: &TurnHandle) -> Result<(), ModelError> {
        self.validate(handle)?;
        self.active.remove(&handle.object_key);
        Ok(())
    }

    pub fn cutover(&mut self, next_epoch: u64) {
        assert!(next_epoch > self.owner_epoch);
        self.owner_epoch = next_epoch;
        self.active.clear();
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LeaseGrant {
    pub holder: String,
    pub fencing_token: u64,
    pub expires_at_tick: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LockReply {
    Acquired(LeaseGrant),
    Renewed(LeaseGrant),
    Released,
    AlreadyReleased,
    Busy,
}

#[derive(Debug, Default)]
pub struct DurableLockModel {
    current: Option<LeaseGrant>,
    next_fencing_token: u64,
    replay: HashMap<String, LockReply>,
}

impl DurableLockModel {
    pub fn acquire(
        &mut self,
        request_id: &str,
        holder: &str,
        now_tick: u64,
        lease_ticks: u64,
    ) -> LockReply {
        if let Some(reply) = self.replay.get(request_id) {
            return reply.clone();
        }
        self.expire(now_tick);
        if self.current.is_some() {
            return LockReply::Busy;
        }

        self.next_fencing_token = self.next_fencing_token.saturating_add(1);
        let grant = LeaseGrant {
            holder: holder.to_owned(),
            fencing_token: self.next_fencing_token,
            expires_at_tick: now_tick.saturating_add(lease_ticks),
        };
        self.current = Some(grant.clone());
        let reply = LockReply::Acquired(grant);
        self.replay.insert(request_id.to_owned(), reply.clone());
        reply
    }

    pub fn renew(
        &mut self,
        request_id: &str,
        holder: &str,
        fencing_token: u64,
        now_tick: u64,
        lease_ticks: u64,
    ) -> Result<LockReply, ModelError> {
        if let Some(reply) = self.replay.get(request_id) {
            return Ok(reply.clone());
        }
        self.expire(now_tick);
        let Some(current) = self.current.as_mut() else {
            return Err(ModelError::NotHolder);
        };
        if current.holder != holder {
            return Err(ModelError::NotHolder);
        }
        if current.fencing_token != fencing_token {
            return Err(ModelError::StaleFencingToken);
        }

        current.expires_at_tick = now_tick.saturating_add(lease_ticks);
        let reply = LockReply::Renewed(current.clone());
        self.replay.insert(request_id.to_owned(), reply.clone());
        Ok(reply)
    }

    pub fn release(
        &mut self,
        request_id: &str,
        holder: &str,
        fencing_token: u64,
        now_tick: u64,
    ) -> Result<LockReply, ModelError> {
        if let Some(reply) = self.replay.get(request_id) {
            return Ok(reply.clone());
        }
        self.expire(now_tick);
        let Some(current) = self.current.as_ref() else {
            let reply = LockReply::AlreadyReleased;
            self.replay.insert(request_id.to_owned(), reply.clone());
            return Ok(reply);
        };
        if current.holder != holder {
            return Err(ModelError::NotHolder);
        }
        if current.fencing_token != fencing_token {
            return Err(ModelError::StaleFencingToken);
        }

        self.current = None;
        let reply = LockReply::Released;
        self.replay.insert(request_id.to_owned(), reply.clone());
        Ok(reply)
    }

    fn expire(&mut self, now_tick: u64) {
        if self
            .current
            .as_ref()
            .is_some_and(|grant| grant.expires_at_tick <= now_tick)
        {
            self.current = None;
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QueueEntry {
    pub sequence: u64,
    pub term: u64,
    pub payload: String,
}

#[derive(Debug)]
pub struct QueuePartitionModel {
    term: u64,
    entries: Vec<QueueEntry>,
    committed_sequence: u64,
}

impl QueuePartitionModel {
    pub fn new(term: u64) -> Self {
        Self {
            term,
            entries: Vec::new(),
            committed_sequence: 0,
        }
    }

    pub fn append(&mut self, term: u64, payload: &str) -> Result<u64, ModelError> {
        if term != self.term {
            return Err(ModelError::StaleTerm);
        }
        let sequence = self.entries.last().map_or(1, |entry| entry.sequence + 1);
        self.entries.push(QueueEntry {
            sequence,
            term,
            payload: payload.to_owned(),
        });
        Ok(sequence)
    }

    pub fn commit_through(&mut self, term: u64, sequence: u64) -> Result<(), ModelError> {
        if term != self.term {
            return Err(ModelError::StaleTerm);
        }
        if sequence < self.committed_sequence
            || !self.entries.iter().any(|entry| entry.sequence == sequence)
        {
            return Err(ModelError::NotCommitted);
        }
        self.committed_sequence = sequence;
        Ok(())
    }

    pub fn promote(&mut self, next_term: u64) {
        assert!(next_term > self.term);
        self.term = next_term;
        self.entries
            .retain(|entry| entry.sequence <= self.committed_sequence);
    }

    pub fn handoff_ready(&self, destination_sequence: u64) -> bool {
        destination_sequence >= self.committed_sequence
    }

    pub fn committed_sequence(&self) -> u64 {
        self.committed_sequence
    }
}

#[derive(Debug, Default)]
pub struct PhoenixGenerationModel {
    active_generation: u64,
    socket_generation: HashMap<String, u64>,
}

impl PhoenixGenerationModel {
    pub fn new(active_generation: u64) -> Self {
        Self {
            active_generation,
            socket_generation: HashMap::new(),
        }
    }

    pub fn connect(&mut self, socket_id: &str) -> u64 {
        let generation = self.active_generation;
        self.socket_generation
            .insert(socket_id.to_owned(), generation);
        generation
    }

    pub fn deploy(&mut self, next_generation: u64) {
        assert!(next_generation > self.active_generation);
        self.active_generation = next_generation;
    }

    pub fn disconnect(&mut self, socket_id: &str) {
        self.socket_generation.remove(socket_id);
    }

    pub fn can_gc(&self, generation: u64) -> bool {
        generation != self.active_generation
            && !self
                .socket_generation
                .values()
                .any(|pinned| *pinned == generation)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn actor_same_object_turn_is_non_preemptive() {
        let mut actor = DurableActorModel::new(7);
        let first = actor.begin_turn("cart/42", "r1").expect("first turn");
        assert_eq!(actor.begin_turn("cart/42", "r2"), Err(ModelError::Busy));
        assert_eq!(actor.validate(&first), Ok(()));
        assert_eq!(actor.complete_turn(&first), Ok(()));
        assert!(actor.begin_turn("cart/42", "r2").is_ok());
    }

    #[test]
    fn actor_unrelated_objects_do_not_invalidate_each_other() {
        let mut actor = DurableActorModel::new(7);
        let first = actor.begin_turn("a", "r1").expect("a turn");
        let second = actor.begin_turn("b", "r2").expect("b turn");
        assert_eq!(actor.validate(&first), Ok(()));
        assert_eq!(actor.validate(&second), Ok(()));
    }

    #[test]
    fn actor_cutover_fences_old_turns() {
        let mut actor = DurableActorModel::new(7);
        let old = actor.begin_turn("a", "r1").expect("old turn");
        actor.cutover(8);
        assert_eq!(actor.validate(&old), Err(ModelError::StaleEpoch));
        assert!(actor.begin_turn("a", "r2").is_ok());
    }

    #[test]
    fn lock_acquire_retry_replays_same_fencing_token() {
        let mut locks = DurableLockModel::default();
        let first = locks.acquire("req-acquire", "worker-a", 100, 20);
        let retry = locks.acquire("req-acquire", "worker-a", 105, 20);
        assert_eq!(first, retry);
    }

    #[test]
    fn lock_renew_retry_does_not_extend_twice() {
        let mut locks = DurableLockModel::default();
        let LockReply::Acquired(grant) = locks.acquire("a1", "worker-a", 100, 20) else {
            panic!("expected acquisition");
        };
        let first = locks
            .renew("renew-1", "worker-a", grant.fencing_token, 110, 20)
            .expect("renew");
        let retry = locks
            .renew("renew-1", "worker-a", grant.fencing_token, 119, 20)
            .expect("renew replay");
        assert_eq!(first, retry);
    }

    #[test]
    fn lock_release_retry_is_idempotent() {
        let mut locks = DurableLockModel::default();
        let LockReply::Acquired(grant) = locks.acquire("a1", "worker-a", 100, 20) else {
            panic!("expected acquisition");
        };
        let first = locks
            .release("release-1", "worker-a", grant.fencing_token, 110)
            .expect("release");
        let retry = locks
            .release("release-1", "worker-a", grant.fencing_token, 111)
            .expect("release replay");
        assert_eq!(first, LockReply::Released);
        assert_eq!(retry, first);
    }

    #[test]
    fn lock_expiry_never_reuses_fencing_token() {
        let mut locks = DurableLockModel::default();
        let LockReply::Acquired(first) = locks.acquire("a1", "worker-a", 100, 5) else {
            panic!("expected first acquisition");
        };
        let LockReply::Acquired(second) = locks.acquire("a2", "worker-b", 106, 5) else {
            panic!("expected second acquisition");
        };
        assert!(second.fencing_token > first.fencing_token);
    }

    #[test]
    fn queue_failover_preserves_committed_prefix_and_rejects_stale_term() {
        let mut queue = QueuePartitionModel::new(4);
        assert_eq!(queue.append(4, "m1"), Ok(1));
        assert_eq!(queue.append(4, "m2"), Ok(2));
        assert_eq!(queue.commit_through(4, 1), Ok(()));
        queue.promote(5);
        assert_eq!(queue.committed_sequence(), 1);
        assert_eq!(queue.append(4, "stale"), Err(ModelError::StaleTerm));
        assert_eq!(queue.append(5, "m3"), Ok(2));
    }

    #[test]
    fn queue_handoff_requires_destination_at_committed_barrier() {
        let mut queue = QueuePartitionModel::new(2);
        assert_eq!(queue.append(2, "m1"), Ok(1));
        assert_eq!(queue.append(2, "m2"), Ok(2));
        assert_eq!(queue.commit_through(2, 2), Ok(()));
        assert!(!queue.handoff_ready(1));
        assert!(queue.handoff_ready(2));
        assert!(queue.handoff_ready(3));
    }

    #[test]
    fn phoenix_old_socket_pins_old_generation_until_disconnect() {
        let mut phoenix = PhoenixGenerationModel::new(41);
        assert_eq!(phoenix.connect("socket-old"), 41);
        phoenix.deploy(42);
        assert_eq!(phoenix.connect("socket-new"), 42);
        assert!(!phoenix.can_gc(41));
        phoenix.disconnect("socket-old");
        assert!(phoenix.can_gc(41));
        assert!(!phoenix.can_gc(42));
    }
}
