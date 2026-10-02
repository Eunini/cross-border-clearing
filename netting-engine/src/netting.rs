//! Multilateral netting for one clearing cycle.
//!
//! Complexity for `n` obligations, `k` participants and `c` currencies:
//! `O(n)` aggregation (three hash lookups per obligation), `O(k log k)` to sort
//! participants, `O(c * k)` for currency positions and `O(k log k)` for the
//! heuristic settlement plan (or `O(2^k * k)` when `k <= 20` and the exact plan
//! is used). Memory is `O(n)` only for the duplicate-id check; everything else
//! is `O(c * k)`.

use crate::fx::{Converter, FxRate, allocate_largest_remainder};
use crate::hash::{FxHashMap, FxHashSet};
use crate::settlement::{self, PlanMethod};
use crate::{EngineError, Minor};
use serde::{Deserialize, Serialize};
use std::time::Instant;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Obligation {
    /// Caller-assigned unique id (the gateway uses its payment id).
    pub id: u64,
    pub debtor: String,
    pub creditor: String,
    /// ISO 4217 code of `amount`.
    pub currency: String,
    /// Amount owed, in minor units of `currency`; must be positive.
    pub amount: Minor,
    /// Value in settlement-currency minor units fixed at FX-quote time.
    /// Required for `FxMode::Precomputed` unless `currency` is the settlement
    /// currency.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub settlement_amount: Option<Minor>,
}

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum FxMode {
    /// Use each obligation's `settlementAmount` (contractual quoted value).
    #[default]
    Precomputed,
    /// Convert each obligation at the supplied cycle rates, round half-even.
    PerObligation,
    /// Net per currency first, convert the nets, apportion rounding residual.
    NetThenConvert,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NettingRequest {
    pub cycle_id: String,
    pub settlement_currency: String,
    #[serde(default = "default_exponent")]
    pub settlement_exponent: u32,
    #[serde(default)]
    pub fx_mode: FxMode,
    #[serde(default)]
    pub rates: Vec<FxRate>,
    pub obligations: Vec<Obligation>,
}

fn default_exponent() -> u32 {
    2
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ParticipantPosition {
    pub participant: String,
    /// Gross value the participant owes (settlement currency).
    pub gross_debit: Minor,
    /// Gross value owed to the participant (settlement currency).
    pub gross_credit: Minor,
    /// Net position: positive = receives, negative = pays.
    pub net: Minor,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CurrencyPosition {
    pub currency: String,
    pub participant: String,
    pub gross_debit: Minor,
    pub gross_credit: Minor,
    pub net: Minor,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CurrencySummary {
    pub currency: String,
    pub obligation_count: u64,
    /// Sum of obligations in this currency (minor units of the currency).
    pub gross: Minor,
    /// Sum of positive per-participant nets in this currency.
    pub net: Minor,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SettlementTransfer {
    pub from: String,
    pub to: String,
    pub amount: Minor,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct NettingStats {
    pub obligation_count: u64,
    pub participant_count: u64,
    pub currency_count: u64,
    /// Liquidity a gross (RTGS, payment-by-payment) settlement would move.
    pub gross_settlement_value: Minor,
    /// Liquidity needed after netting: sum of net debit positions.
    pub net_settlement_value: Minor,
    pub transfer_count: u64,
    pub transfer_lower_bound: u64,
    pub transfers_optimal: bool,
    pub plan_method: PlanMethod,
    /// 1 - transfers / obligations, in percent.
    pub transfer_reduction_pct: f64,
    /// 1 - net / gross settlement value, in percent.
    pub liquidity_saving_pct: f64,
    pub compute_micros: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NettingResult {
    pub cycle_id: String,
    pub settlement_currency: String,
    pub fx_mode: FxMode,
    pub positions: Vec<ParticipantPosition>,
    pub currency_positions: Vec<CurrencyPosition>,
    pub currency_summaries: Vec<CurrencySummary>,
    pub transfers: Vec<SettlementTransfer>,
    pub stats: NettingStats,
}

fn add(a: Minor, b: Minor) -> Result<Minor, EngineError> {
    a.checked_add(b)
        .ok_or(EngineError::Overflow("aggregating obligations"))
}

fn sub(a: Minor, b: Minor) -> Result<Minor, EngineError> {
    a.checked_sub(b)
        .ok_or(EngineError::Overflow("aggregating obligations"))
}

/// Returns the first id (in input order) that repeats an earlier one.
///
/// Callers typically use database sequence values, so ids are dense; then a
/// bitmap over `[min, max]` is used (sequential memory access, 1 bit per id).
/// Sparse ids fall back to a hash set.
fn find_duplicate(ids: impl Iterator<Item = u64> + Clone) -> Option<u64> {
    let (mut min, mut max, mut n) = (u64::MAX, 0u64, 0u64);
    for id in ids.clone() {
        min = min.min(id);
        max = max.max(id);
        n += 1;
    }
    if n == 0 {
        return None;
    }
    let span = max - min;
    if span < n.saturating_mul(16) {
        let mut bits = vec![0u64; (span / 64 + 1) as usize];
        for id in ids {
            let k = id - min;
            let (word, bit) = ((k / 64) as usize, 1u64 << (k % 64));
            if bits[word] & bit != 0 {
                return Some(id);
            }
            bits[word] |= bit;
        }
        None
    } else {
        let mut seen: FxHashSet<u64> =
            FxHashSet::with_capacity_and_hasher(n as usize, Default::default());
        ids.into_iter().find(|&id| !seen.insert(id))
    }
}

/// Assigns dense indices to keys in first-seen order.
struct Interner<'a> {
    index: FxHashMap<&'a str, u32>,
    names: Vec<&'a str>,
}

impl<'a> Interner<'a> {
    fn new() -> Self {
        Interner {
            index: FxHashMap::default(),
            names: Vec::new(),
        }
    }

    #[inline]
    fn get(&mut self, key: &'a str) -> u32 {
        if let Some(&i) = self.index.get(key) {
            return i;
        }
        let i = self.names.len() as u32;
        self.index.insert(key, i);
        self.names.push(key);
        i
    }

    /// Names in lexicographic order and `rank[first_seen_index] = sorted_index`,
    /// so output does not depend on input order.
    fn sorted(self) -> (Vec<&'a str>, Vec<usize>) {
        let mut order: Vec<usize> = (0..self.names.len()).collect();
        order.sort_unstable_by_key(|&i| self.names[i]);
        let mut rank = vec![0usize; order.len()];
        for (sorted_pos, &i) in order.iter().enumerate() {
            rank[i] = sorted_pos;
        }
        (order.iter().map(|&i| self.names[i]).collect(), rank)
    }
}

pub fn net(req: &NettingRequest) -> Result<NettingResult, EngineError> {
    let started = Instant::now();
    let obligations = &req.obligations;

    // Pass 1: validate and intern (three hash lookups per obligation).
    let mut pint = Interner::new();
    let mut cint = Interner::new();
    let mut keys: Vec<(u32, u32, u32)> = Vec::with_capacity(obligations.len());
    if let Some(id) = find_duplicate(obligations.iter().map(|o| o.id)) {
        return Err(EngineError::DuplicateId(id));
    }
    for o in obligations {
        if o.amount <= 0 {
            return Err(EngineError::NonPositiveAmount(o.id));
        }
        if o.debtor == o.creditor {
            return Err(EngineError::SelfObligation(o.id));
        }
        keys.push((
            pint.get(&o.debtor),
            pint.get(&o.creditor),
            cint.get(&o.currency),
        ));
    }
    let (participants, prank) = pint.sorted();
    let (currencies, crank) = cint.sorted();
    let np = participants.len();
    let nc = currencies.len();

    // Converter per currency (only used by the converting modes).
    let rates: FxHashMap<&str, &FxRate> =
        req.rates.iter().map(|r| (r.currency.as_str(), r)).collect();
    let mut converters = Vec::with_capacity(nc);
    for &c in &currencies {
        if c == req.settlement_currency || req.fx_mode == FxMode::Precomputed {
            converters.push(Converter::identity());
        } else {
            let r = rates
                .get(c)
                .ok_or_else(|| EngineError::MissingRate(c.to_string()))?;
            converters.push(Converter::new(r, req.settlement_exponent)?);
        }
    }

    // Pass 2: aggregate using the dense, sorted indices.
    let mut cur_net = vec![0 as Minor; nc * np];
    let mut cur_debit = vec![0 as Minor; nc * np];
    let mut cur_credit = vec![0 as Minor; nc * np];
    let mut cur_count = vec![0u64; nc];
    let mut s_net = vec![0 as Minor; np];
    let mut s_debit = vec![0 as Minor; np];
    let mut s_credit = vec![0 as Minor; np];
    let mut gross: Minor = 0;
    let converting_per_obligation = req.fx_mode != FxMode::NetThenConvert;

    for (o, &(d, cr, c)) in obligations.iter().zip(&keys) {
        let (d, cr, c) = (prank[d as usize], prank[cr as usize], crank[c as usize]);
        let (dk, ck) = (c * np + d, c * np + cr);
        cur_net[dk] = sub(cur_net[dk], o.amount)?;
        cur_net[ck] = add(cur_net[ck], o.amount)?;
        cur_debit[dk] = add(cur_debit[dk], o.amount)?;
        cur_credit[ck] = add(cur_credit[ck], o.amount)?;
        cur_count[c] += 1;

        if converting_per_obligation {
            let s = match req.fx_mode {
                FxMode::Precomputed => {
                    if o.currency == req.settlement_currency {
                        o.settlement_amount.unwrap_or(o.amount)
                    } else {
                        o.settlement_amount
                            .ok_or(EngineError::MissingSettlementAmount(o.id))?
                    }
                }
                _ => converters[c].convert(o.amount)?,
            };
            if s <= 0 {
                return Err(EngineError::NonPositiveAmount(o.id));
            }
            s_net[d] = sub(s_net[d], s)?;
            s_net[cr] = add(s_net[cr], s)?;
            s_debit[d] = add(s_debit[d], s)?;
            s_credit[cr] = add(s_credit[cr], s)?;
            gross = add(gross, s)?;
        }
    }

    if !converting_per_obligation {
        // Net then convert, apportioning the rounding residual per currency.
        for c in 0..nc {
            let conv = converters[c];
            let row = &cur_net[c * np..(c + 1) * np];
            let exact = row
                .iter()
                .map(|&v| conv.exact(v))
                .collect::<Result<Vec<_>, _>>()?;
            let alloc = allocate_largest_remainder(&exact)?;
            for p in 0..np {
                s_net[p] = add(s_net[p], alloc[p])?;
                let d = conv.convert(cur_debit[c * np + p])?;
                let cr = conv.convert(cur_credit[c * np + p])?;
                s_debit[p] = add(s_debit[p], d)?;
                s_credit[p] = add(s_credit[p], cr)?;
                gross = add(gross, d)?;
            }
        }
    }

    let plan = settlement::plan(&s_net);
    let net_value: Minor = s_net.iter().filter(|&&v| v > 0).sum();

    let positions = (0..np)
        .map(|p| ParticipantPosition {
            participant: participants[p].to_string(),
            gross_debit: s_debit[p],
            gross_credit: s_credit[p],
            net: s_net[p],
        })
        .collect();
    let mut currency_positions = Vec::new();
    let mut currency_summaries = Vec::with_capacity(nc);
    for c in 0..nc {
        let mut c_gross = 0;
        let mut c_net = 0;
        for (p, name) in participants.iter().enumerate() {
            let k = c * np + p;
            if cur_debit[k] == 0 && cur_credit[k] == 0 {
                continue;
            }
            c_gross = add(c_gross, cur_debit[k])?;
            if cur_net[k] > 0 {
                c_net = add(c_net, cur_net[k])?;
            }
            currency_positions.push(CurrencyPosition {
                currency: currencies[c].to_string(),
                participant: name.to_string(),
                gross_debit: cur_debit[k],
                gross_credit: cur_credit[k],
                net: cur_net[k],
            });
        }
        currency_summaries.push(CurrencySummary {
            currency: currencies[c].to_string(),
            obligation_count: cur_count[c],
            gross: c_gross,
            net: c_net,
        });
    }
    let transfers = plan
        .transfers
        .iter()
        .map(|t| SettlementTransfer {
            from: participants[t.from].to_string(),
            to: participants[t.to].to_string(),
            amount: t.amount,
        })
        .collect::<Vec<_>>();

    let pct = |part: f64, whole: f64| {
        if whole == 0.0 {
            0.0
        } else {
            ((1.0 - part / whole) * 10_000.0).round() / 100.0
        }
    };
    let stats = NettingStats {
        obligation_count: obligations.len() as u64,
        participant_count: np as u64,
        currency_count: nc as u64,
        gross_settlement_value: gross,
        net_settlement_value: net_value,
        transfer_count: transfers.len() as u64,
        transfer_lower_bound: plan.lower_bound as u64,
        transfers_optimal: plan.optimal,
        plan_method: plan.method,
        transfer_reduction_pct: pct(transfers.len() as f64, obligations.len() as f64),
        liquidity_saving_pct: pct(net_value as f64, gross as f64),
        compute_micros: started.elapsed().as_micros() as u64,
    };
    Ok(NettingResult {
        cycle_id: req.cycle_id.clone(),
        settlement_currency: req.settlement_currency.clone(),
        fx_mode: req.fx_mode,
        positions,
        currency_positions,
        currency_summaries,
        transfers,
        stats,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use rust_decimal::Decimal;

    fn ob(id: u64, d: &str, c: &str, ccy: &str, amt: Minor, s: Option<Minor>) -> Obligation {
        Obligation {
            id,
            debtor: d.into(),
            creditor: c.into(),
            currency: ccy.into(),
            amount: amt,
            settlement_amount: s,
        }
    }

    fn req(mode: FxMode, obligations: Vec<Obligation>) -> NettingRequest {
        NettingRequest {
            cycle_id: "c1".into(),
            settlement_currency: "USD".into(),
            settlement_exponent: 2,
            fx_mode: mode,
            rates: vec![
                FxRate {
                    currency: "INR".into(),
                    rate: Decimal::from(80),
                    exponent: 2,
                },
                FxRate {
                    currency: "MXN".into(),
                    rate: Decimal::from(20),
                    exponent: 2,
                },
            ],
            obligations,
        }
    }

    #[test]
    fn nets_a_simple_triangle() {
        // A owes B 100, B owes C 100, C owes A 100 -> everything nets to zero.
        let r = net(&req(
            FxMode::Precomputed,
            vec![
                ob(1, "A", "B", "USD", 100, None),
                ob(2, "B", "C", "USD", 100, None),
                ob(3, "C", "A", "USD", 100, None),
            ],
        ))
        .unwrap();
        assert!(r.positions.iter().all(|p| p.net == 0));
        assert!(r.transfers.is_empty());
        assert_eq!(r.stats.gross_settlement_value, 300);
        assert_eq!(r.stats.net_settlement_value, 0);
        assert_eq!(r.stats.liquidity_saving_pct, 100.0);
    }

    #[test]
    fn precomputed_mode_uses_quoted_values() {
        let r = net(&req(
            FxMode::Precomputed,
            vec![
                ob(1, "IN1", "MX1", "INR", 8_000, Some(100)),
                ob(2, "MX1", "IN1", "MXN", 800, Some(40)),
            ],
        ))
        .unwrap();
        let in1 = r.positions.iter().find(|p| p.participant == "IN1").unwrap();
        assert_eq!(in1.net, -60);
        assert_eq!(
            r.transfers,
            vec![SettlementTransfer {
                from: "IN1".into(),
                to: "MX1".into(),
                amount: 60
            }]
        );
    }

    #[test]
    fn precomputed_mode_requires_settlement_amount() {
        let e = net(&req(
            FxMode::Precomputed,
            vec![ob(7, "A", "B", "INR", 10, None)],
        ))
        .unwrap_err();
        assert_eq!(e, EngineError::MissingSettlementAmount(7));
    }

    #[test]
    fn converting_modes_agree_on_exact_rates() {
        let obligations = vec![
            ob(1, "IN1", "MX1", "INR", 8_000, None),  // 1.00 USD
            ob(2, "MX1", "IN1", "MXN", 4_000, None),  // 2.00 USD
            ob(3, "IN2", "IN1", "INR", 16_000, None), // 2.00 USD
        ];
        let a = net(&req(FxMode::PerObligation, obligations.clone())).unwrap();
        let b = net(&req(FxMode::NetThenConvert, obligations)).unwrap();
        assert_eq!(a.positions, b.positions);
        assert_eq!(a.stats.gross_settlement_value, 500);
    }

    #[test]
    fn rejects_bad_input() {
        assert_eq!(
            net(&req(
                FxMode::Precomputed,
                vec![ob(1, "A", "A", "USD", 1, None)]
            ))
            .unwrap_err(),
            EngineError::SelfObligation(1)
        );
        assert_eq!(
            net(&req(
                FxMode::Precomputed,
                vec![ob(1, "A", "B", "USD", 0, None)]
            ))
            .unwrap_err(),
            EngineError::NonPositiveAmount(1)
        );
        assert_eq!(
            net(&req(
                FxMode::Precomputed,
                vec![
                    ob(1, "A", "B", "USD", 1, None),
                    ob(1, "B", "A", "USD", 1, None)
                ]
            ))
            .unwrap_err(),
            EngineError::DuplicateId(1)
        );
        assert_eq!(
            net(&req(
                FxMode::PerObligation,
                vec![ob(1, "A", "B", "CHF", 1, None)]
            ))
            .unwrap_err(),
            EngineError::MissingRate("CHF".into())
        );
    }

    #[test]
    fn duplicate_detection_handles_dense_and_sparse_ids() {
        assert_eq!(find_duplicate([5u64, 6, 7, 6].into_iter()), Some(6));
        assert_eq!(find_duplicate([5u64, 6, 7, 8].into_iter()), None);
        assert_eq!(
            find_duplicate([1u64, u64::MAX, 3, u64::MAX].into_iter()),
            Some(u64::MAX)
        );
        assert_eq!(find_duplicate([1u64, u64::MAX].into_iter()), None);
        assert_eq!(find_duplicate(std::iter::empty()), None);
    }

    #[test]
    fn output_is_independent_of_input_order() {
        let mut obs: Vec<Obligation> = (0..50)
            .map(|i| {
                ob(
                    i,
                    &format!("P{}", i % 7),
                    &format!("P{}", (i * 3 + 1) % 7),
                    "USD",
                    (i as i64 + 1) * 10,
                    None,
                )
            })
            .filter(|o| o.debtor != o.creditor)
            .collect();
        let a = net(&req(FxMode::Precomputed, obs.clone())).unwrap();
        obs.reverse();
        let b = net(&req(FxMode::Precomputed, obs)).unwrap();
        assert_eq!(a.positions, b.positions);
        assert_eq!(a.transfers, b.transfers);
    }
}
