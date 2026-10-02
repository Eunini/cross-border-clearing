//! Deterministic synthetic workloads for benchmarks and tests.
//!
//! Uses a self-contained SplitMix64 generator so results are reproducible
//! across platforms without pulling a RNG crate into the service binary.

use crate::lsm::{Account, QueuedPayment};
use crate::netting::Obligation;

pub const CURRENCIES: [&str; 12] = [
    "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "SGD", "HKD", "INR", "BRL", "MXN",
];

pub struct SplitMix64(u64);

impl SplitMix64 {
    pub fn new(seed: u64) -> Self {
        SplitMix64(seed)
    }
    pub fn next_u64(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }
    /// Uniform in [0, 1).
    pub fn next_f64(&mut self) -> f64 {
        (self.next_u64() >> 11) as f64 / (1u64 << 53) as f64
    }
    pub fn below(&mut self, n: u64) -> u64 {
        self.next_u64() % n
    }
}

pub fn participant(i: usize) -> String {
    format!("P{i:02}")
}

/// Skewed participant pick: participant `i` has weight `1 / (i + 1)`.
fn pick(rng: &mut SplitMix64, cumulative: &[f64]) -> usize {
    let x = rng.next_f64() * cumulative[cumulative.len() - 1];
    cumulative
        .partition_point(|&c| c <= x)
        .min(cumulative.len() - 1)
}

/// `n` obligations among `participants` banks with Zipf-like activity and
/// log-normal-ish sizes (median ~ 300.00 settlement units, long tail).
pub fn obligations(n: usize, participants: usize, seed: u64) -> Vec<Obligation> {
    assert!(participants >= 2);
    let mut rng = SplitMix64::new(seed);
    let mut acc = 0.0;
    let cumulative: Vec<f64> = (0..participants)
        .map(|i| {
            acc += 1.0 / (i as f64 + 1.0);
            acc
        })
        .collect();
    let names: Vec<String> = (0..participants).map(participant).collect();
    (0..n)
        .map(|i| {
            let d = pick(&mut rng, &cumulative);
            let mut c = pick(&mut rng, &cumulative);
            if c == d {
                c = (d + 1 + rng.below(participants as u64 - 1) as usize) % participants;
            }
            // Sum of 4 uniforms approximates a normal; exponentiate for a log-normal.
            let z: f64 = (0..4).map(|_| rng.next_f64()).sum::<f64>() - 2.0;
            let settlement = ((30_000.0 * (z * 2.2).exp()) as i64).max(100);
            Obligation {
                id: i as u64,
                debtor: names[d].clone(),
                creditor: names[c].clone(),
                currency: CURRENCIES[d % CURRENCIES.len()].to_string(),
                amount: settlement * 1000 / 7 + 1,
                settlement_amount: Some(settlement),
            }
        })
        .collect()
}

/// A gridlock-prone queue: participants with zero headroom and many
/// payments queued in all directions.
pub fn lsm_workload(
    n: usize,
    participants: usize,
    seed: u64,
) -> (Vec<Account>, Vec<QueuedPayment>) {
    let obligations = obligations(n, participants, seed);
    let mut rng = SplitMix64::new(seed ^ 0xA5A5);
    let accounts = (0..participants)
        .map(|i| Account {
            participant: participant(i),
            balance: 0,
            limit: rng.below(500_000) as i64,
        })
        .collect();
    let queue = obligations
        .into_iter()
        .map(|o| QueuedPayment {
            id: o.id,
            debtor: o.debtor,
            creditor: o.creditor,
            amount: o.settlement_amount.unwrap_or(o.amount),
        })
        .collect();
    (accounts, queue)
}
