use thiserror::Error;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum EngineError {
    #[error("obligation {0}: amount must be positive")]
    NonPositiveAmount(u64),
    #[error("obligation {0}: debtor and creditor must differ")]
    SelfObligation(u64),
    #[error("obligation {0}: settlement amount missing (fxMode=PRECOMPUTED)")]
    MissingSettlementAmount(u64),
    #[error("no FX rate supplied for currency {0}")]
    MissingRate(String),
    #[error("invalid FX rate for currency {0}: must be positive")]
    InvalidRate(String),
    #[error("arithmetic overflow while {0}")]
    Overflow(&'static str),
    #[error("participant {0}: limit must be >= 0")]
    NegativeLimit(String),
    #[error("queued payment {0} references unknown participant {1}")]
    UnknownParticipant(u64, String),
    #[error("duplicate id {0}")]
    DuplicateId(u64),
    #[error("duplicate participant {0}")]
    DuplicateParticipant(String),
}
