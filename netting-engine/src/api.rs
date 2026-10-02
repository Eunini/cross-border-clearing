//! HTTP/JSON API.
//!
//! * `POST /v1/netting`      - [`NettingRequest`] -> [`NettingResult`]
//! * `POST /v1/lsm/resolve`  - [`LsmRequest`] -> [`LsmResult`]
//! * `GET  /health`
//!
//! Both computations are CPU-bound, so they run on the blocking thread pool
//! to keep the async reactor responsive. Validation errors map to `422`.

use crate::EngineError;
use crate::lsm::{self, LsmRequest, LsmResult};
use crate::netting::{self, NettingRequest, NettingResult};
use axum::extract::DefaultBodyLimit;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::json;

/// Large cycles (hundreds of thousands of obligations) exceed axum's 2 MiB default.
pub const MAX_BODY_BYTES: usize = 512 * 1024 * 1024;

pub fn router() -> Router {
    Router::new()
        .route("/health", get(|| async { Json(json!({ "status": "UP" })) }))
        .route("/v1/netting", post(netting_handler))
        .route("/v1/lsm/resolve", post(lsm_handler))
        .layer(DefaultBodyLimit::max(MAX_BODY_BYTES))
}

pub struct ApiError(StatusCode, String);

impl From<EngineError> for ApiError {
    fn from(e: EngineError) -> Self {
        ApiError(StatusCode::UNPROCESSABLE_ENTITY, e.to_string())
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(json!({ "error": self.1 }))).into_response()
    }
}

async fn netting_handler(Json(req): Json<NettingRequest>) -> Result<Json<NettingResult>, ApiError> {
    let cycle = req.cycle_id.clone();
    let n = req.obligations.len();
    let result = tokio::task::spawn_blocking(move || netting::net(&req))
        .await
        .map_err(|e| ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))??;
    tracing::info!(
        cycle = %cycle,
        obligations = n,
        transfers = result.stats.transfer_count,
        micros = result.stats.compute_micros,
        "netting complete"
    );
    Ok(Json(result))
}

async fn lsm_handler(Json(req): Json<LsmRequest>) -> Result<Json<LsmResult>, ApiError> {
    let result = tokio::task::spawn_blocking(move || lsm::resolve(&req))
        .await
        .map_err(|e| ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))??;
    tracing::info!(
        queued = result.stats.queued,
        released = result.stats.released,
        by_offsetting = result.stats.released_by_offsetting,
        "lsm run complete"
    );
    Ok(Json(result))
}
