#[derive(Debug, Clone, PartialEq, Eq)]
struct LockState {
    version: u64,
    holder: Option<String>,
    fencing_token: u64,
    expiry_tick: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PersistMode {
    Success,
    FailBeforeCommit,
    AmbiguousAfterCommit,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum PersistError {
    FailedBeforeCommit,
    AmbiguousAfterCommit,
    StaleVersion,
}

#[derive(Debug)]
struct Store {
    durable: LockState,
}

impl Store {
    fn new() -> Self {
        Self {
            durable: LockState {
                version: 0,
                holder: None,
                fencing_token: 0,
                expiry_tick: 0,
            },
        }
    }

    fn commit(
        &mut self,
        expected_version: u64,
        mut candidate: LockState,
        mode: PersistMode,
    ) -> Result<LockState, PersistError> {
        if self.durable.version != expected_version {
            return Err(PersistError::StaleVersion);
        }
        if mode == PersistMode::FailBeforeCommit {
            return Err(PersistError::FailedBeforeCommit);
        }

        candidate.version = expected_version + 1;
        self.durable = candidate.clone();
        if mode == PersistMode::AmbiguousAfterCommit {
            return Err(PersistError::AmbiguousAfterCommit);
        }
        Ok(candidate)
    }

    fn load(&self) -> LockState {
        self.durable.clone()
    }
}

#[derive(Debug)]
struct LockActor {
    state: LockState,
    authoritative: bool,
}

impl LockActor {
    fn load(store: &Store) -> Self {
        Self {
            state: store.load(),
            authoritative: true,
        }
    }

    fn acquire(
        &mut self,
        store: &mut Store,
        holder: &str,
        expiry_tick: u64,
        mode: PersistMode,
    ) -> Result<(), PersistError> {
        assert!(self.authoritative);
        assert!(self.state.holder.is_none());
        let mut candidate = self.state.clone();
        candidate.holder = Some(holder.to_owned());
        candidate.fencing_token += 1;
        candidate.expiry_tick = expiry_tick;
        self.persist_or_fail_stop(store, candidate, mode)
    }

    fn renew(
        &mut self,
        store: &mut Store,
        expiry_tick: u64,
        mode: PersistMode,
    ) -> Result<(), PersistError> {
        assert!(self.authoritative);
        assert!(self.state.holder.is_some());
        let mut candidate = self.state.clone();
        candidate.expiry_tick = expiry_tick;
        self.persist_or_fail_stop(store, candidate, mode)
    }

    fn release(&mut self, store: &mut Store, mode: PersistMode) -> Result<(), PersistError> {
        assert!(self.authoritative);
        assert!(self.state.holder.is_some());
        let mut candidate = self.state.clone();
        candidate.holder = None;
        candidate.expiry_tick = 0;
        self.persist_or_fail_stop(store, candidate, mode)
    }

    fn persist_or_fail_stop(
        &mut self,
        store: &mut Store,
        candidate: LockState,
        mode: PersistMode,
    ) -> Result<(), PersistError> {
        match store.commit(self.state.version, candidate, mode) {
            Ok(next) => {
                self.state = next;
                Ok(())
            }
            Err(error) => {
                self.authoritative = false;
                Err(error)
            }
        }
    }
}

#[test]
fn ambiguous_acquire_fail_stops_and_reload_observes_committed_grant() {
    let mut store = Store::new();
    let mut actor = LockActor::load(&store);
    assert_eq!(
        actor.acquire(
            &mut store,
            "holder-a",
            200,
            PersistMode::AmbiguousAfterCommit
        ),
        Err(PersistError::AmbiguousAfterCommit)
    );
    assert!(!actor.authoritative);

    let replacement = LockActor::load(&store);
    assert_eq!(replacement.state.version, 1);
    assert_eq!(replacement.state.holder.as_deref(), Some("holder-a"));
    assert_eq!(replacement.state.fencing_token, 1);
}

#[test]
fn ambiguous_renew_fail_stops_and_reload_observes_new_expiry() {
    let mut store = Store::new();
    let mut actor = LockActor::load(&store);
    assert_eq!(
        actor.acquire(&mut store, "holder-a", 200, PersistMode::Success),
        Ok(())
    );
    assert_eq!(
        actor.renew(&mut store, 500, PersistMode::AmbiguousAfterCommit),
        Err(PersistError::AmbiguousAfterCommit)
    );
    assert!(!actor.authoritative);

    let replacement = LockActor::load(&store);
    assert_eq!(replacement.state.version, 2);
    assert_eq!(replacement.state.expiry_tick, 500);
    assert_eq!(replacement.state.fencing_token, 1);
}

#[test]
fn ambiguous_release_fail_stops_and_reload_observes_free_lock() {
    let mut store = Store::new();
    let mut actor = LockActor::load(&store);
    assert_eq!(
        actor.acquire(&mut store, "holder-a", 200, PersistMode::Success),
        Ok(())
    );
    assert_eq!(
        actor.release(&mut store, PersistMode::AmbiguousAfterCommit),
        Err(PersistError::AmbiguousAfterCommit)
    );
    assert!(!actor.authoritative);

    let replacement = LockActor::load(&store);
    assert_eq!(replacement.state.version, 2);
    assert_eq!(replacement.state.holder, None);
    assert_eq!(replacement.state.expiry_tick, 0);
    assert_eq!(replacement.state.fencing_token, 1);
}

#[test]
fn known_precommit_failure_also_fail_stops_instead_of_guessing() {
    let mut store = Store::new();
    let mut actor = LockActor::load(&store);
    assert_eq!(
        actor.acquire(
            &mut store,
            "holder-a",
            200,
            PersistMode::FailBeforeCommit
        ),
        Err(PersistError::FailedBeforeCommit)
    );
    assert!(!actor.authoritative);

    let replacement = LockActor::load(&store);
    assert_eq!(replacement.state.version, 0);
    assert_eq!(replacement.state.holder, None);
    assert_eq!(replacement.state.fencing_token, 0);
}
