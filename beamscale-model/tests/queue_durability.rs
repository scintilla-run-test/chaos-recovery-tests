use std::collections::HashMap;

#[derive(Debug, Clone, PartialEq, Eq)]
enum QueueError {
    StaleTerm,
    UnknownReplica,
    ReplicaAhead,
}

#[derive(Debug)]
struct QueueModel {
    term: u64,
    quorum: usize,
    replicas: Vec<String>,
    entries: HashMap<u64, u64>,
    durable: HashMap<String, u64>,
    last_sequence: u64,
    committed_sequence: u64,
}

impl QueueModel {
    fn new(term: u64, replicas: &[&str], quorum: usize) -> Self {
        let replica_names: Vec<String> = replicas.iter().map(|value| (*value).to_owned()).collect();
        let durable = replica_names
            .iter()
            .map(|replica| (replica.clone(), 0))
            .collect();
        Self {
            term,
            quorum,
            replicas: replica_names,
            entries: HashMap::new(),
            durable,
            last_sequence: 0,
            committed_sequence: 0,
        }
    }

    fn append(&mut self, term: u64) -> Result<u64, QueueError> {
        if term != self.term {
            return Err(QueueError::StaleTerm);
        }
        self.last_sequence += 1;
        self.entries.insert(self.last_sequence, term);
        Ok(self.last_sequence)
    }

    fn record_durable(
        &mut self,
        replica: &str,
        term: u64,
        sequence: u64,
    ) -> Result<(), QueueError> {
        if term != self.term {
            return Err(QueueError::StaleTerm);
        }
        if !self.replicas.iter().any(|known| known == replica) {
            return Err(QueueError::UnknownReplica);
        }
        if sequence > self.last_sequence {
            return Err(QueueError::ReplicaAhead);
        }
        let current = *self.durable.get(replica).expect("known replica");
        if sequence >= current {
            self.durable.insert(replica.to_owned(), sequence);
        }
        self.advance_commit();
        Ok(())
    }

    fn advance_commit(&mut self) {
        let mut progress: Vec<u64> = self.durable.values().copied().collect();
        progress.sort_unstable_by(|left, right| right.cmp(left));
        let candidate = progress[self.quorum - 1];
        let mut next = candidate;
        while next > self.committed_sequence {
            if self.entries.get(&next) == Some(&self.term) {
                self.committed_sequence = next;
                return;
            }
            next -= 1;
        }
    }

    fn begin_term(&mut self, next_term: u64, retained_last_sequence: u64) {
        assert!(next_term > self.term);
        assert!(retained_last_sequence >= self.committed_sequence);
        assert!(retained_last_sequence <= self.last_sequence);
        self.entries
            .retain(|sequence, _| *sequence <= retained_last_sequence);
        for progress in self.durable.values_mut() {
            *progress = (*progress).min(retained_last_sequence);
        }
        self.last_sequence = retained_last_sequence;
        self.term = next_term;
    }
}

#[test]
fn quorum_one_append_stays_pending_until_local_durable_ack() {
    let mut queue = QueueModel::new(7, &["leader"], 1);
    assert_eq!(queue.append(7), Ok(1));
    assert_eq!(queue.committed_sequence, 0);
    assert_eq!(queue.record_durable("leader", 7, 1), Ok(()));
    assert_eq!(queue.committed_sequence, 1);
}

#[test]
fn quorum_two_requires_two_explicit_durable_acks() {
    let mut queue = QueueModel::new(7, &["leader", "follower-a", "follower-b"], 2);
    assert_eq!(queue.append(7), Ok(1));
    assert_eq!(queue.record_durable("leader", 7, 1), Ok(()));
    assert_eq!(queue.committed_sequence, 0);
    assert_eq!(queue.record_durable("follower-a", 7, 1), Ok(()));
    assert_eq!(queue.committed_sequence, 1);
}

#[test]
fn follower_fsync_without_leader_fsync_is_not_a_quorum_of_two() {
    let mut queue = QueueModel::new(7, &["leader", "follower-a", "follower-b"], 2);
    assert_eq!(queue.append(7), Ok(1));
    assert_eq!(queue.record_durable("follower-a", 7, 1), Ok(()));
    assert_eq!(queue.committed_sequence, 0);
    assert_eq!(queue.record_durable("leader", 7, 1), Ok(()));
    assert_eq!(queue.committed_sequence, 1);
}

#[test]
fn failover_may_drop_durable_but_uncommitted_suffix() {
    let mut queue = QueueModel::new(7, &["leader", "follower-a", "follower-b"], 2);
    assert_eq!(queue.append(7), Ok(1));
    assert_eq!(queue.record_durable("leader", 7, 1), Ok(()));
    assert_eq!(queue.committed_sequence, 0);
    queue.begin_term(8, 0);
    assert_eq!(queue.last_sequence, 0);
    assert_eq!(queue.committed_sequence, 0);
    assert_eq!(queue.append(8), Ok(1));
}

#[test]
fn stale_term_cannot_create_or_certify_durable_progress() {
    let mut queue = QueueModel::new(7, &["leader", "follower"], 2);
    assert_eq!(queue.append(7), Ok(1));
    queue.begin_term(8, 0);
    assert_eq!(queue.append(7), Err(QueueError::StaleTerm));
    assert_eq!(
        queue.record_durable("leader", 7, 0),
        Err(QueueError::StaleTerm)
    );
}
