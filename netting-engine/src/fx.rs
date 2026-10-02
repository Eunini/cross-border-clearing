//! Conversion of minor-unit amounts into the settlement currency.
//!
//! Two strategies are supported by the netting module:
//!
//! * **Per obligation** - every obligation is converted individually and
//!   rounded half-to-even. Because the *same* rounded value is debited to the
//!   debtor and credited to the creditor, conservation (sum of nets == 0) holds
//!   exactly regardless of rounding.
//! * **Net then convert** - each participant's per-currency net is converted
//!   once. Rounding the converted nets independently would break conservation
//!   by up to n/2 minor units, so [`allocate_largest_remainder`] distributes
//!   the rounding residual (Hamilton / largest-remainder apportionment). Every
//!   result is within one minor unit of its exact value and the results sum to
//!   exactly the rounded exact total (zero for a balanced input).

use crate::{EngineError, Minor};
use rust_decimal::{Decimal, RoundingStrategy};
use serde::{Deserialize, Serialize};

/// A cycle rate for one currency, expressed as units of `currency` (major
/// units) per one major unit of the settlement currency, e.g. `INR 84.05`
/// against USD.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct FxRate {
    pub currency: String,
    pub rate: Decimal,
    /// ISO 4217 minor-unit exponent of `currency` (2 for INR, 0 for JPY).
    pub exponent: u32,
}

/// Precomputed divisor for one currency: `rate * 10^exponent / 10^settlement_exponent`.
#[derive(Debug, Clone, Copy)]
pub struct Converter {
    divisor: Decimal,
}

impl Converter {
    pub fn identity() -> Self {
        Converter {
            divisor: Decimal::ONE,
        }
    }

    pub fn new(rate: &FxRate, settlement_exponent: u32) -> Result<Self, EngineError> {
        if rate.rate <= Decimal::ZERO {
            return Err(EngineError::InvalidRate(rate.currency.clone()));
        }
        let scale_num = pow10(rate.exponent)?;
        let scale_den = pow10(settlement_exponent)?;
        let divisor = rate
            .rate
            .checked_mul(scale_num)
            .and_then(|v| v.checked_div(scale_den))
            .ok_or(EngineError::Overflow("building FX converter"))?;
        Ok(Converter { divisor })
    }

    /// Exact (unrounded, 28 significant digits) settlement-currency value.
    pub fn exact(&self, amount: Minor) -> Result<Decimal, EngineError> {
        Decimal::from(amount)
            .checked_div(self.divisor)
            .ok_or(EngineError::Overflow("converting amount"))
    }

    /// Settlement-currency value rounded half-to-even (banker's rounding).
    pub fn convert(&self, amount: Minor) -> Result<Minor, EngineError> {
        to_minor(
            self.exact(amount)?
                .round_dp_with_strategy(0, RoundingStrategy::MidpointNearestEven),
        )
    }
}

fn pow10(exp: u32) -> Result<Decimal, EngineError> {
    if exp > 18 {
        return Err(EngineError::Overflow("exponent too large"));
    }
    Ok(Decimal::from(10i64.pow(exp)))
}

fn to_minor(d: Decimal) -> Result<Minor, EngineError> {
    use rust_decimal::prelude::ToPrimitive;
    d.to_i64()
        .ok_or(EngineError::Overflow("converting decimal to minor units"))
}

/// Round a vector of exact values to integers such that the integers sum to
/// `round(sum(values))` and each integer is `floor(v)` or `floor(v) + 1`.
///
/// Ties between equal remainders are broken by index so the result is
/// deterministic.
pub fn allocate_largest_remainder(values: &[Decimal]) -> Result<Vec<Minor>, EngineError> {
    let mut floors = Vec::with_capacity(values.len());
    let mut remainders: Vec<(Decimal, usize)> = Vec::with_capacity(values.len());
    let mut remainder_sum = Decimal::ZERO;
    for (i, v) in values.iter().enumerate() {
        let f = v.floor();
        let r = *v - f;
        remainder_sum += r;
        floors.push(to_minor(f)?);
        remainders.push((r, i));
    }
    let extra =
        to_minor(remainder_sum.round_dp_with_strategy(0, RoundingStrategy::MidpointNearestEven))?;
    let extra = usize::try_from(extra.max(0)).unwrap_or(0).min(values.len());
    remainders.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
    for &(_, i) in remainders.iter().take(extra) {
        floors[i] += 1;
    }
    Ok(floors)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::str::FromStr;

    fn rate(c: &str, r: &str, e: u32) -> FxRate {
        FxRate {
            currency: c.into(),
            rate: Decimal::from_str(r).unwrap(),
            exponent: e,
        }
    }

    #[test]
    fn converts_two_decimal_currency() {
        // 8,405.00 INR at 84.05 INR/USD = 100.00 USD
        let c = Converter::new(&rate("INR", "84.05", 2), 2).unwrap();
        assert_eq!(c.convert(840_500).unwrap(), 10_000);
    }

    #[test]
    fn converts_zero_decimal_currency() {
        // 150 JPY at 150 JPY/USD = 1.00 USD
        let c = Converter::new(&rate("JPY", "150", 0), 2).unwrap();
        assert_eq!(c.convert(150).unwrap(), 100);
    }

    #[test]
    fn rounds_half_to_even() {
        // 1 JPY at 200 JPY/USD = 0.005 USD = 0.5 cents -> 0 (even)
        let c = Converter::new(&rate("JPY", "200", 0), 2).unwrap();
        assert_eq!(c.convert(1).unwrap(), 0);
        // 3 JPY = 1.5 cents -> 2 (even)
        assert_eq!(c.convert(3).unwrap(), 2);
        assert_eq!(c.convert(-3).unwrap(), -2);
    }

    #[test]
    fn rejects_non_positive_rate() {
        assert!(Converter::new(&rate("MXN", "0", 2), 2).is_err());
        assert!(Converter::new(&rate("MXN", "-1", 2), 2).is_err());
    }

    #[test]
    fn largest_remainder_preserves_zero_sum() {
        let third = Decimal::ONE / Decimal::from(3);
        let vals = vec![third, third, third, -Decimal::ONE];
        let out = allocate_largest_remainder(&vals).unwrap();
        assert_eq!(out.iter().sum::<i64>(), 0);
        assert_eq!(out, vec![1, 0, 0, -1]);
    }
}
