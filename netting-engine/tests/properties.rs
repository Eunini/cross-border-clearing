//! Property-based tests for the invariants the clearing network relies on.

use netting_engine::fx::FxRate;
use netting_engine::lsm::{self, Account, LsmRequest, QueuedPayment};
use netting_engine::netting::{self, FxMode, NettingRequest, Obligation};
use netting_engine::settlement::{self, PlanMethod};
use proptest::prelude::*;
use rust_decimal::Decimal;
use std::collections::{HashMap, HashSet};

const CCYS: [(&str, i64, u32); 4] = [
    ("INR", 84_050, 3),
    ("JPY", 149_730, 3),
    ("MXN", 18_455, 3),
    ("USD", 1, 0),
];

fn rates() -> Vec<FxRate> {
    CCYS.iter()
        .map(|&(c, mantissa, scale)| FxRate {
            currency: c.into(),
            rate: Decimal::new(mantissa, scale),
            exponent: if c == "JPY" { 0 } else { 2 },
        })
        .collect()
}

prop_compose! {
    fn obligation_strategy(participants: usize)(
        d in 0..participants,
        offset in 1..participants,
        ccy in 0..CCYS.len(),
        amount in 1i64..2_000_000_000,
        settlement in 1i64..50_000_000,
    ) -> (usize, usize, usize, i64, i64) {
        (d, (d + offset) % participants, ccy, amount, settlement)
    }
}

fn obligations_strategy() -> impl Strategy<Value = Vec<Obligation>> {
    (2usize..14).prop_flat_map(|p| {
        prop::collection::vec(obligation_strategy(p), 1..300).prop_map(|raw| {
            raw.into_iter()
                .enumerate()
                .map(|(i, (d, c, ccy, amount, s))| Obligation {
                    id: i as u64,
                    debtor: format!("BANK{d:02}"),
                    creditor: format!("BANK{c:02}"),
                    currency: CCYS[ccy].0.into(),
                    amount,
                    settlement_amount: Some(s),
                })
                .collect()
        })
    })
}

fn request(mode: FxMode, obligations: Vec<Obligation>) -> NettingRequest {
    NettingRequest {
        cycle_id: "prop".into(),
        settlement_currency: "USD".into(),
        settlement_exponent: 2,
        fx_mode: mode,
        rates: rates(),
        obligations,
    }
}

fn check_netting_invariants(req: &NettingRequest) -> Result<(), TestCaseError> {
    let r = netting::net(req).unwrap();

    // 1. Conservation in the settlement currency.
    let total: i128 = r.positions.iter().map(|p| p.net as i128).sum();
    prop_assert_eq!(total, 0);

    // 2. Conservation per currency, and per-currency nets match a naive recomputation.
    let mut naive: HashMap<(String, String), i128> = HashMap::new();
    for o in &req.obligations {
        *naive
            .entry((o.currency.clone(), o.debtor.clone()))
            .or_default() -= o.amount as i128;
        *naive
            .entry((o.currency.clone(), o.creditor.clone()))
            .or_default() += o.amount as i128;
    }
    let mut per_ccy: HashMap<&str, i128> = HashMap::new();
    for cp in &r.currency_positions {
        *per_ccy.entry(cp.currency.as_str()).or_default() += cp.net as i128;
        prop_assert_eq!(
            naive[&(cp.currency.clone(), cp.participant.clone())],
            cp.net as i128
        );
    }
    prop_assert!(per_ccy.values().all(|&v| v == 0));

    // 3. Transfers reproduce net positions exactly, move value only from
    //    payers to receivers, and total exactly the net settlement value.
    let mut from_transfers: HashMap<&str, i128> = HashMap::new();
    let mut moved: i128 = 0;
    for t in &r.transfers {
        prop_assert!(t.amount > 0);
        *from_transfers.entry(t.from.as_str()).or_default() -= t.amount as i128;
        *from_transfers.entry(t.to.as_str()).or_default() += t.amount as i128;
        moved += t.amount as i128;
    }
    let net_of: HashMap<&str, i64> = r
        .positions
        .iter()
        .map(|p| (p.participant.as_str(), p.net))
        .collect();
    for p in &r.positions {
        prop_assert_eq!(
            from_transfers
                .get(p.participant.as_str())
                .copied()
                .unwrap_or(0),
            p.net as i128
        );
    }
    for t in &r.transfers {
        prop_assert!(net_of[t.from.as_str()] < 0 && net_of[t.to.as_str()] > 0);
    }
    prop_assert_eq!(moved, r.stats.net_settlement_value as i128);

    // 4. Plan quality bounds.
    let nonzero = r.positions.iter().filter(|p| p.net != 0).count() as u64;
    prop_assert!(r.stats.transfer_count >= r.stats.transfer_lower_bound);
    prop_assert!(r.stats.transfer_count <= nonzero.saturating_sub(1));
    prop_assert!(r.stats.net_settlement_value <= r.stats.gross_settlement_value);
    Ok(())
}

/// Independent brute force: maximum number of disjoint zero-sum groups.
fn max_zero_sum_groups(values: &[i64]) -> usize {
    fn go(rest: &[i64]) -> usize {
        if rest.is_empty() {
            return 0;
        }
        // The group containing rest[0]: try every subset of the others.
        let first = rest[0];
        let others = &rest[1..];
        let mut best = 0;
        for mask in 0u32..(1 << others.len()) {
            let s: i64 = first
                + (0..others.len())
                    .filter(|i| mask & (1 << i) != 0)
                    .map(|i| others[i])
                    .sum::<i64>();
            if s == 0 {
                let remaining: Vec<i64> = (0..others.len())
                    .filter(|i| mask & (1 << i) == 0)
                    .map(|i| others[i])
                    .collect();
                best = best.max(1 + go(&remaining));
            }
        }
        best
    }
    go(values)
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(256))]

    #[test]
    fn precomputed_netting_conserves_value(obs in obligations_strategy()) {
        check_netting_invariants(&request(FxMode::Precomputed, obs))?;
    }

    #[test]
    fn per_obligation_fx_netting_conserves_value(obs in obligations_strategy()) {
        check_netting_invariants(&request(FxMode::PerObligation, obs))?;
    }

    #[test]
    fn net_then_convert_conserves_value_and_stays_within_rounding(obs in obligations_strategy()) {
        let req = request(FxMode::NetThenConvert, obs);
        check_netting_invariants(&req)?;
        // Each participant's settlement net is within one minor unit per
        // currency of the exact (unrounded) converted value.
        let r = netting::net(&req).unwrap();
        let rate_of: HashMap<String, FxRate> = rates().into_iter().map(|r| (r.currency.clone(), r)).collect();
        let mut exact: HashMap<String, Decimal> = HashMap::new();
        for cp in &r.currency_positions {
            let rt = &rate_of[&cp.currency];
            let v = if cp.currency == "USD" {
                Decimal::from(cp.net)
            } else {
                Decimal::from(cp.net) * Decimal::from(100) / (rt.rate * Decimal::from(10i64.pow(rt.exponent)))
            };
            *exact.entry(cp.participant.clone()).or_default() += v;
        }
        let ccys = r.currency_summaries.len() as i64;
        for p in &r.positions {
            let diff = (Decimal::from(p.net) - exact[&p.participant]).abs();
            prop_assert!(diff <= Decimal::from(ccys), "participant {} off by {}", p.participant, diff);
        }
    }

    #[test]
    fn settlement_plan_reproduces_arbitrary_zero_sum_positions(
        mut nets in prop::collection::vec(-1_000_000i64..1_000_000, 1..60)
    ) {
        let s: i64 = nets.iter().sum();
        nets.push(-s);
        let plan = settlement::plan(&nets);
        let back = settlement::apply(nets.len(), &plan.transfers);
        for (i, &n) in nets.iter().enumerate() {
            prop_assert_eq!(back[i], n as i128);
        }
        let k = nets.iter().filter(|&&v| v != 0).count();
        prop_assert!(plan.transfers.len() <= k.saturating_sub(1));
        prop_assert!(plan.transfers.len() >= plan.lower_bound);
    }

    #[test]
    fn exact_plan_matches_brute_force_optimum(
        mut nets in prop::collection::vec(-6i64..6, 1..9)
    ) {
        // Small magnitudes make zero-sum subgroups common.
        let s: i64 = nets.iter().sum();
        nets.push(-s);
        let plan = settlement::plan(&nets);
        let nonzero: Vec<i64> = nets.iter().copied().filter(|&v| v != 0).collect();
        let optimum = nonzero.len() - max_zero_sum_groups(&nonzero);
        if !nonzero.is_empty() {
            prop_assert_eq!(plan.method, PlanMethod::ExactDp);
        }
        prop_assert_eq!(plan.transfers.len(), optimum);
        prop_assert!(plan.optimal);
    }
}

// ---------------------------------------------------------------- LSM

fn lsm_strategy(max_payments: usize) -> impl Strategy<Value = LsmRequest> {
    (2usize..7).prop_flat_map(move |k| {
        let accounts = prop::collection::vec((-500i64..500, 0i64..400), k);
        let queue = prop::collection::vec((0..k, 1..k, 1i64..600), 0..max_payments);
        (accounts, queue).prop_map(move |(accounts, queue)| LsmRequest {
            accounts: accounts
                .into_iter()
                .enumerate()
                .map(|(i, (balance, limit))| Account {
                    participant: format!("B{i}"),
                    balance,
                    limit,
                })
                .collect(),
            queue: queue
                .into_iter()
                .enumerate()
                .map(|(id, (d, off, amount))| QueuedPayment {
                    id: id as u64,
                    debtor: format!("B{d}"),
                    creditor: format!("B{}", (d + off) % k),
                    amount,
                })
                .collect(),
        })
    })
}

fn positions_after(req: &LsmRequest, released: &HashSet<u64>) -> HashMap<String, i64> {
    let mut pos: HashMap<String, i64> = req
        .accounts
        .iter()
        .map(|a| (a.participant.clone(), a.balance))
        .collect();
    for p in req.queue.iter().filter(|p| released.contains(&p.id)) {
        *pos.get_mut(&p.debtor).unwrap() -= p.amount;
        *pos.get_mut(&p.creditor).unwrap() += p.amount;
    }
    pos
}

fn feasible(req: &LsmRequest, released: &HashSet<u64>) -> bool {
    let pos = positions_after(req, released);
    req.accounts
        .iter()
        .all(|a| pos[&a.participant] >= a.balance.min(-a.limit))
}

fn fifo_closed(req: &LsmRequest, released: &HashSet<u64>) -> bool {
    let mut blocked: HashSet<&str> = HashSet::new();
    for p in &req.queue {
        if released.contains(&p.id) {
            if blocked.contains(p.debtor.as_str()) {
                return false;
            }
        } else {
            blocked.insert(p.debtor.as_str());
        }
    }
    true
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(512))]

    #[test]
    fn lsm_never_violates_limits_and_preserves_fifo(req in lsm_strategy(60)) {
        let r = lsm::resolve(&req).unwrap();
        let released: HashSet<u64> = r.released.iter().copied().collect();
        prop_assert!(feasible(&req, &released));
        prop_assert!(fifo_closed(&req, &released));
        // Reported balances match an independent recomputation, and value is conserved.
        let pos = positions_after(&req, &released);
        for b in &r.final_balances {
            prop_assert_eq!(pos[&b.participant], b.balance);
        }
        let before: i64 = req.accounts.iter().map(|a| a.balance).sum();
        let after: i64 = r.final_balances.iter().map(|b| b.balance).sum();
        prop_assert_eq!(before, after);
        prop_assert_eq!(r.released.len() + r.unresolved.len(), req.queue.len());
    }

    #[test]
    fn lsm_release_is_the_maximum_fifo_preserving_set(req in lsm_strategy(11)) {
        let r = lsm::resolve(&req).unwrap();
        let released: HashSet<u64> = r.released.iter().copied().collect();
        // Every feasible FIFO-closed subset must be contained in the result.
        let n = req.queue.len();
        for mask in 0u32..(1 << n) {
            let s: HashSet<u64> = (0..n).filter(|i| mask & (1 << i) != 0).map(|i| req.queue[i].id).collect();
            if fifo_closed(&req, &s) && feasible(&req, &s) {
                prop_assert!(s.is_subset(&released), "feasible set {:?} not within {:?}", s, released);
            }
        }
    }
}
