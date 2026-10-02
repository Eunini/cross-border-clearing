//! Turning multilateral net positions into settlement transfers.
//!
//! Input: a vector of net positions `n[p]` (positive = net receiver,
//! negative = net payer) with `sum(n) == 0`.
//!
//! Output: transfers `(from, to, amount)` such that for every participant
//! `inflow - outflow == n[p]`.
//!
//! **Value.** Every plan produced here only moves money from net payers to
//! net receivers, so the total value transferred equals `sum(max(n, 0))`,
//! which is the minimum possible: each receiver must receive at least its net
//! and nothing passes through an intermediary.
//!
//! **Count.** Minimising the *number* of transfers is NP-hard (partition
//! reduces to it). With `k` non-zero participants the optimum is
//! `k - g`, where `g` is the maximum number of disjoint zero-sum groups the
//! participants can be partitioned into (each group of size `s` needs exactly
//! `s - 1` transfers, and no fewer for a group with no zero-sum subgroup).
//!
//! * `k <= EXACT_LIMIT`: an `O(2^k * k)` subset dynamic programme finds `g`
//!   exactly and reconstructs the groups - the result is provably optimal.
//! * otherwise: exact-amount pairs are matched first (each such pair is a
//!   zero-sum group of size two and saves one transfer versus greedy), then
//!   the remainder is settled greedily largest-payer to largest-receiver,
//!   which needs at most `m - 1` transfers for `m` remaining participants.
//!   The plan reports a lower bound `max(#payers, #receivers)` so callers can
//!   see the worst-case gap.

use crate::Minor;
use serde::Serialize;

/// Largest number of non-zero positions for which the exact DP is used.
/// 2^20 * 20 ~ 21M simple operations, a few milliseconds.
pub const EXACT_LIMIT: usize = 20;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct Transfer {
    pub from: usize,
    pub to: usize,
    pub amount: Minor,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PlanMethod {
    /// Nothing to settle.
    Empty,
    /// Exact subset DP (optimal number of transfers).
    ExactDp,
    /// Exact pair matching + greedy (bounded heuristic).
    PairingGreedy,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Plan {
    pub transfers: Vec<Transfer>,
    pub method: PlanMethod,
    /// A proven lower bound on the number of transfers any plan needs.
    pub lower_bound: usize,
    /// True when `transfers.len()` is proven minimal.
    pub optimal: bool,
}

pub fn plan(nets: &[Minor]) -> Plan {
    let nonzero: Vec<usize> = (0..nets.len()).filter(|&i| nets[i] != 0).collect();
    if nonzero.is_empty() {
        return Plan {
            transfers: vec![],
            method: PlanMethod::Empty,
            lower_bound: 0,
            optimal: true,
        };
    }
    if nonzero.len() <= EXACT_LIMIT {
        plan_exact(nets, &nonzero)
    } else {
        plan_heuristic(nets, &nonzero)
    }
}

/// Greedy settlement of one zero-sum group: largest payer pays largest
/// receiver until one side is exhausted. Produces at most `len - 1` transfers.
fn settle_group(nets: &[Minor], members: &[usize], out: &mut Vec<Transfer>) {
    let mut payers: Vec<(usize, Minor)> = members
        .iter()
        .filter(|&&i| nets[i] < 0)
        .map(|&i| (i, -nets[i]))
        .collect();
    let mut receivers: Vec<(usize, Minor)> = members
        .iter()
        .filter(|&&i| nets[i] > 0)
        .map(|&i| (i, nets[i]))
        .collect();
    // Largest first, ties by index for determinism.
    payers.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    receivers.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    let (mut i, mut j) = (0, 0);
    while i < payers.len() && j < receivers.len() {
        let amount = payers[i].1.min(receivers[j].1);
        out.push(Transfer {
            from: payers[i].0,
            to: receivers[j].0,
            amount,
        });
        payers[i].1 -= amount;
        receivers[j].1 -= amount;
        if payers[i].1 == 0 {
            i += 1;
        }
        if receivers[j].1 == 0 {
            j += 1;
        }
    }
    debug_assert!(
        i == payers.len() && j == receivers.len(),
        "group was not zero-sum"
    );
}

fn plan_exact(nets: &[Minor], nonzero: &[usize]) -> Plan {
    let k = nonzero.len();
    let full = (1usize << k) - 1;
    // sum[mask] computed incrementally from the lowest set bit.
    let mut sum = vec![0i128; full + 1];
    for mask in 1..=full {
        let low = mask.trailing_zeros() as usize;
        sum[mask] = sum[mask & (mask - 1)] + nets[nonzero[low]] as i128;
    }
    // dp[mask] = max number of zero-sum "prefix closings" over orderings of mask
    //          = max number of disjoint zero-sum groups partitioning mask
    //            (valid when sum[mask] == 0).
    let mut dp = vec![0u8; full + 1];
    for mask in 1..=full {
        let mut best = 0u8;
        let mut m = mask;
        while m != 0 {
            let bit = m & m.wrapping_neg();
            best = best.max(dp[mask ^ bit]);
            m ^= bit;
        }
        dp[mask] = best + u8::from(sum[mask] == 0);
    }
    // Reconstruct an ordering whose zero-sum prefixes give the groups.
    let mut order = Vec::with_capacity(k);
    let mut mask = full;
    while mask != 0 {
        let target = dp[mask] - u8::from(sum[mask] == 0);
        let mut m = mask;
        loop {
            let bit = m & m.wrapping_neg();
            if dp[mask ^ bit] == target {
                order.push(bit.trailing_zeros() as usize);
                mask ^= bit;
                break;
            }
            m ^= bit;
        }
    }
    order.reverse();
    let mut transfers = Vec::with_capacity(k);
    let mut group: Vec<usize> = Vec::new();
    let mut running: i128 = 0;
    for &local in &order {
        let p = nonzero[local];
        group.push(p);
        running += nets[p] as i128;
        if running == 0 {
            settle_group(nets, &group, &mut transfers);
            group.clear();
        }
    }
    debug_assert!(group.is_empty());
    let groups = dp[full] as usize;
    Plan {
        lower_bound: k - groups,
        optimal: true,
        method: PlanMethod::ExactDp,
        transfers,
    }
}

fn plan_heuristic(nets: &[Minor], nonzero: &[usize]) -> Plan {
    use std::collections::HashMap;
    let payers = nonzero.iter().filter(|&&i| nets[i] < 0).count();
    let receivers = nonzero.len() - payers;
    let mut transfers = Vec::with_capacity(nonzero.len());

    // 1. Exact pairs: a payer owing exactly what a receiver is owed.
    let mut by_amount: HashMap<Minor, Vec<usize>> = HashMap::new();
    for &i in nonzero.iter().filter(|&&i| nets[i] > 0) {
        by_amount.entry(nets[i]).or_default().push(i);
    }
    for v in by_amount.values_mut() {
        v.reverse(); // pop() yields lowest index first
    }
    let mut paired = vec![false; nets.len()];
    for &i in nonzero.iter().filter(|&&i| nets[i] < 0) {
        if let Some(r) = by_amount.get_mut(&-nets[i]).and_then(|v| v.pop()) {
            transfers.push(Transfer {
                from: i,
                to: r,
                amount: -nets[i],
            });
            paired[i] = true;
            paired[r] = true;
        }
    }
    // 2. Greedy on the rest (still zero-sum because removed pairs were).
    let rest: Vec<usize> = nonzero.iter().copied().filter(|&i| !paired[i]).collect();
    settle_group(nets, &rest, &mut transfers);

    let lower_bound = payers.max(receivers);
    Plan {
        optimal: transfers.len() == lower_bound,
        lower_bound,
        method: PlanMethod::PairingGreedy,
        transfers,
    }
}

/// Recompute net positions from transfers (used by tests and by callers that
/// want to verify a plan).
pub fn apply(n: usize, transfers: &[Transfer]) -> Vec<i128> {
    let mut out = vec![0i128; n];
    for t in transfers {
        out[t.from] -= t.amount as i128;
        out[t.to] += t.amount as i128;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn check(nets: &[Minor]) -> Plan {
        let p = plan(nets);
        let back = apply(nets.len(), &p.transfers);
        for (i, &n) in nets.iter().enumerate() {
            assert_eq!(back[i], n as i128, "participant {i}");
        }
        assert!(p.transfers.iter().all(|t| t.amount > 0));
        assert!(p.transfers.len() >= p.lower_bound);
        p
    }

    #[test]
    fn empty_plan() {
        let p = check(&[0, 0, 0]);
        assert!(p.transfers.is_empty());
        assert_eq!(p.method, PlanMethod::Empty);
    }

    #[test]
    fn exact_dp_finds_two_groups() {
        // {A:-5, B:+5} and {C:-3, D:-4, E:+7}: optimal 1 + 2 = 3 transfers.
        let p = check(&[-5, 5, -3, -4, 7]);
        assert_eq!(p.transfers.len(), 3);
        assert!(p.optimal);
        assert_eq!(p.method, PlanMethod::ExactDp);
    }

    #[test]
    fn exact_dp_beats_plain_greedy() {
        // Plain largest-first greedy needs 4 transfers here:
        //   7->5 (5), 7->3 (2), 3->3 (1), 3->2 (2)
        // but {-3,+3} and {-7,+5,+2} are zero-sum groups: 1 + 2 = 3 transfers.
        let nets = [-7, -3, 5, 3, 2];
        let mut greedy = Vec::new();
        settle_group(&nets, &[0, 1, 2, 3, 4], &mut greedy);
        assert_eq!(greedy.len(), 4);
        let p = check(&nets);
        assert_eq!(p.transfers.len(), 3);
        assert_eq!(p.lower_bound, 3);
        assert!(p.optimal);
    }

    #[test]
    fn exact_dp_handles_three_way_groups() {
        // Groups {-5,+3,+2} and {-5,+7,-2}: 2 + 2 = 4 transfers.
        let p = check(&[-5, -5, 3, 7, -2, 2]);
        assert_eq!(p.transfers.len(), 4);
        assert_eq!(p.lower_bound, 4);
    }

    #[test]
    fn heuristic_used_for_many_participants() {
        let mut nets: Vec<Minor> = (1..=30).map(|i| -(i as Minor)).collect();
        let total: Minor = (1..=30).sum();
        nets.push(total);
        let p = check(&nets);
        assert_eq!(p.method, PlanMethod::PairingGreedy);
        assert_eq!(p.transfers.len(), 30);
        assert!(p.optimal);
    }

    #[test]
    fn heuristic_pairs_exact_matches() {
        let mut nets = Vec::new();
        for i in 1..=15 {
            nets.push(-(i as Minor) * 100);
            nets.push(i as Minor * 100);
        }
        let p = check(&nets);
        assert_eq!(p.transfers.len(), 15);
        assert!(p.optimal);
    }
}
