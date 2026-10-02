//! Liquidity-saving mechanism: gridlock resolution by multilateral offsetting.
//!
//! **Problem.** Each participant `p` has a current balance `b[p]` (its net
//! position in the open cycle) and a limit `L[p] >= 0`; its position must
//! never go below `floor[p] = min(b[p], -L[p])` (a participant that is
//! already beyond its limit may not get worse, but is not forced to improve).
//! Payments that would breach the floor wait in a queue, in priority (FIFO)
//! order per debtor. Queues can gridlock: A waits for B's payment, B waits
//! for C's, C waits for A's - none can settle individually although all of
//! them could settle *simultaneously*.
//!
//! **Algorithm** (the iterative-removal method used by RTGS liquidity-saving
//! mechanisms, cf. Bech & Soramaki 2001, "Gridlock resolution in interbank
//! payment systems"):
//!
//! 1. Tentatively settle every queued payment at once and compute positions.
//! 2. While some participant `p` is below its floor, drop `p`'s *last*
//!    still-included outgoing payment (lowest priority) - which raises `p`'s
//!    position and lowers its creditor's, possibly making the creditor a new
//!    violator.
//! 3. Settle everything still included, atomically.
//!
//! **Soundness.** The loop only ends when nobody is below its floor, so the
//! released set never violates a limit. The empty set is always feasible
//! (positions equal balances, which are >= floors), so a violator always has
//! an included payment left to drop; the loop terminates after at most `n`
//! removals.
//!
//! **Optimality (FIFO-constrained).** Call a set *FIFO-closed* if, per debtor,
//! it is a prefix of that debtor's queue. Let `T` be any feasible FIFO-closed
//! set. Invariant: `T` is a subset of the current set `S`. Suppose `p` violates
//! in `S`. If `S` and `T` had the same prefix for `p`, then `p`'s outflows
//! would be equal and its inflows in `S` at least those in `T` (since `T` is a
//! subset), so `p` would be no worse off in `S` than in `T` - contradiction.
//! Hence `S`'s prefix for `p` is strictly longer and dropping its last
//! payment keeps `T` a subset. So the result contains every feasible
//! FIFO-closed set: it is the unique *maximum* FIFO-preserving solution.
//! (Without the FIFO constraint the maximum-value problem is NP-hard.)
//!
//! **Complexity.** `O(n + k)` time and memory: each payment is removed at
//! most once and every removal is `O(1)` amortised via a worklist.

use crate::hash::{FxHashMap, FxHashSet};
use crate::{EngineError, Minor};
use serde::{Deserialize, Serialize};
use std::time::Instant;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Account {
    pub participant: String,
    /// Current position (positive = net receiver so far).
    pub balance: Minor,
    /// Net debit cap: the position may go down to `-limit`.
    pub limit: Minor,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct QueuedPayment {
    pub id: u64,
    pub debtor: String,
    pub creditor: String,
    pub amount: Minor,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LsmRequest {
    pub accounts: Vec<Account>,
    /// Queued payments in priority order (earlier = higher priority).
    pub queue: Vec<QueuedPayment>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AccountBalance {
    pub participant: String,
    pub balance: Minor,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct LsmStats {
    pub queued: u64,
    pub released: u64,
    pub released_value: Minor,
    /// Released payments that exceeded the debtor's own headroom, i.e. that
    /// could only settle because of simultaneous offsetting inflows.
    pub released_by_offsetting: u64,
    pub removals: u64,
    pub compute_micros: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LsmResult {
    /// Ids to settle simultaneously, in queue order.
    pub released: Vec<u64>,
    /// Ids that stay queued, in queue order.
    pub unresolved: Vec<u64>,
    pub final_balances: Vec<AccountBalance>,
    pub stats: LsmStats,
}

pub fn resolve(req: &LsmRequest) -> Result<LsmResult, EngineError> {
    let started = Instant::now();
    let k = req.accounts.len();
    let mut idx: FxHashMap<&str, usize> =
        FxHashMap::with_capacity_and_hasher(k, Default::default());
    let mut pos = Vec::with_capacity(k);
    let mut floor = Vec::with_capacity(k);
    for (i, a) in req.accounts.iter().enumerate() {
        if a.limit < 0 {
            return Err(EngineError::NegativeLimit(a.participant.clone()));
        }
        if idx.insert(a.participant.as_str(), i).is_some() {
            return Err(EngineError::DuplicateParticipant(a.participant.clone()));
        }
        pos.push(a.balance as i128);
        floor.push((a.balance as i128).min(-(a.limit as i128)));
    }

    let n = req.queue.len();
    let mut debtor = Vec::with_capacity(n);
    let mut creditor = Vec::with_capacity(n);
    let mut outgoing: Vec<Vec<usize>> = vec![Vec::new(); k];
    let mut seen: FxHashSet<u64> = FxHashSet::with_capacity_and_hasher(n, Default::default());
    for (q, p) in req.queue.iter().enumerate() {
        if !seen.insert(p.id) {
            return Err(EngineError::DuplicateId(p.id));
        }
        if p.amount <= 0 {
            return Err(EngineError::NonPositiveAmount(p.id));
        }
        if p.debtor == p.creditor {
            return Err(EngineError::SelfObligation(p.id));
        }
        let d = *idx
            .get(p.debtor.as_str())
            .ok_or_else(|| EngineError::UnknownParticipant(p.id, p.debtor.clone()))?;
        let c = *idx
            .get(p.creditor.as_str())
            .ok_or_else(|| EngineError::UnknownParticipant(p.id, p.creditor.clone()))?;
        debtor.push(d);
        creditor.push(c);
        outgoing[d].push(q);
        // Step 1: tentatively include everything.
        pos[d] -= p.amount as i128;
        pos[c] += p.amount as i128;
    }

    // included[p] = length of p's included queue prefix.
    let mut included: Vec<usize> = outgoing.iter().map(Vec::len).collect();
    let mut in_worklist = vec![false; k];
    let mut worklist: Vec<usize> = Vec::new();
    for p in 0..k {
        if pos[p] < floor[p] {
            in_worklist[p] = true;
            worklist.push(p);
        }
    }
    let mut removals = 0u64;
    // Step 2: drop lowest-priority payments of violators.
    while let Some(p) = worklist.pop() {
        in_worklist[p] = false;
        while pos[p] < floor[p] {
            // Invariant (see module docs): a violator always has an included payment.
            let q = outgoing[p][included[p] - 1];
            included[p] -= 1;
            removals += 1;
            let amount = req.queue[q].amount as i128;
            pos[p] += amount;
            let c = creditor[q];
            pos[c] -= amount;
            if pos[c] < floor[c] && !in_worklist[c] {
                in_worklist[c] = true;
                worklist.push(c);
            }
        }
    }

    // Step 3: collect results in queue order.
    let mut rank = vec![0usize; n]; // position of q within its debtor's queue
    for list in &outgoing {
        for (r, &q) in list.iter().enumerate() {
            rank[q] = r;
        }
    }
    let mut released = Vec::new();
    let mut unresolved = Vec::new();
    let mut released_value: i128 = 0;
    let mut by_offsetting = 0u64;
    let mut own_outflow = vec![0i128; k];
    for (q, p) in req.queue.iter().enumerate() {
        let d = debtor[q];
        if rank[q] < included[d] {
            released.push(p.id);
            released_value += p.amount as i128;
            own_outflow[d] += p.amount as i128;
            let headroom = req.accounts[d].balance as i128 - floor[d];
            if own_outflow[d] > headroom {
                by_offsetting += 1;
            }
        } else {
            unresolved.push(p.id);
        }
    }
    let final_balances = req
        .accounts
        .iter()
        .enumerate()
        .map(|(i, a)| {
            Ok(AccountBalance {
                participant: a.participant.clone(),
                balance: Minor::try_from(pos[i])
                    .map_err(|_| EngineError::Overflow("final balance"))?,
            })
        })
        .collect::<Result<Vec<_>, EngineError>>()?;
    Ok(LsmResult {
        stats: LsmStats {
            queued: n as u64,
            released: released.len() as u64,
            released_value: Minor::try_from(released_value)
                .map_err(|_| EngineError::Overflow("released value"))?,
            released_by_offsetting: by_offsetting,
            removals,
            compute_micros: started.elapsed().as_micros() as u64,
        },
        released,
        unresolved,
        final_balances,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn acct(p: &str, balance: Minor, limit: Minor) -> Account {
        Account {
            participant: p.into(),
            balance,
            limit,
        }
    }
    fn pay(id: u64, d: &str, c: &str, amount: Minor) -> QueuedPayment {
        QueuedPayment {
            id,
            debtor: d.into(),
            creditor: c.into(),
            amount,
        }
    }

    #[test]
    fn resolves_a_three_way_gridlock() {
        // Nobody can pay alone (limit 0, balance 0) but the cycle offsets.
        let r = resolve(&LsmRequest {
            accounts: vec![acct("A", 0, 0), acct("B", 0, 0), acct("C", 0, 0)],
            queue: vec![
                pay(1, "A", "B", 100),
                pay(2, "B", "C", 100),
                pay(3, "C", "A", 100),
            ],
        })
        .unwrap();
        assert_eq!(r.released, vec![1, 2, 3]);
        assert!(r.unresolved.is_empty());
        assert_eq!(r.stats.released_by_offsetting, 3);
        assert!(r.final_balances.iter().all(|b| b.balance == 0));
    }

    #[test]
    fn partial_offsetting_drops_lowest_priority() {
        // A->B 100 and B->A 60 offset only if A can cover 40 (limit 50 ok);
        // A's second payment (30) would take A to -70 < -50, so it is dropped.
        let r = resolve(&LsmRequest {
            accounts: vec![acct("A", 0, 50), acct("B", 0, 0)],
            queue: vec![
                pay(1, "A", "B", 100),
                pay(2, "B", "A", 60),
                pay(3, "A", "B", 30),
            ],
        })
        .unwrap();
        assert_eq!(r.released, vec![1, 2]);
        assert_eq!(r.unresolved, vec![3]);
        let a = r
            .final_balances
            .iter()
            .find(|b| b.participant == "A")
            .unwrap();
        assert_eq!(a.balance, -40);
    }

    #[test]
    fn respects_fifo_even_if_a_later_payment_would_fit() {
        // A's first payment (200) cannot fit; FIFO forbids releasing the second (10).
        let r = resolve(&LsmRequest {
            accounts: vec![acct("A", 0, 50), acct("B", 0, 0)],
            queue: vec![pay(1, "A", "B", 200), pay(2, "A", "B", 10)],
        })
        .unwrap();
        assert!(r.released.is_empty());
        assert_eq!(r.unresolved, vec![1, 2]);
    }

    #[test]
    fn participant_already_beyond_limit_may_not_get_worse() {
        let r = resolve(&LsmRequest {
            accounts: vec![acct("A", -100, 50), acct("B", 0, 0)],
            queue: vec![pay(1, "B", "A", 30), pay(2, "A", "B", 30)],
        })
        .unwrap();
        assert_eq!(r.released, vec![1, 2]);
    }

    #[test]
    fn rejects_unknown_participant() {
        let e = resolve(&LsmRequest {
            accounts: vec![acct("A", 0, 0)],
            queue: vec![pay(1, "A", "Z", 1)],
        })
        .unwrap_err();
        assert_eq!(e, EngineError::UnknownParticipant(1, "Z".into()));
    }
}
