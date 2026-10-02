//! Multilateral netting and liquidity-saving engine.
//!
//! The engine is deliberately stateless: every request carries the full
//! input for one clearing cycle (obligations) or one liquidity-saving run
//! (queued payments plus current balances and limits). That makes every
//! endpoint a pure function of its input, so callers can retry safely and
//! results can be reproduced exactly from a stored request.
//!
//! Modules:
//! * [`netting`]    - per-currency and settlement-currency multilateral netting
//! * [`settlement`] - turning net positions into a small set of settlement transfers
//! * [`fx`]         - deterministic conversion into the settlement currency
//! * [`lsm`]        - gridlock resolution for queued payments (FIFO-preserving)
//! * [`api`]        - HTTP/JSON surface (axum)

pub mod api;
pub mod error;
pub mod fx;
pub mod hash;
pub mod lsm;
pub mod netting;
pub mod settlement;
pub mod synthetic;

pub use error::EngineError;

/// Amounts are always integers in the minor unit of their currency
/// (e.g. cents for USD, paise for INR, yen for JPY which has no minor unit).
pub type Minor = i64;
