use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use netting_engine::api::router;
use serde_json::{Value, json};
use tower::ServiceExt;

async fn post(path: &str, body: Value) -> (StatusCode, Value) {
    let resp = router()
        .oneshot(
            Request::post(path)
                .header("content-type", "application/json")
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = resp.status();
    let bytes = resp.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap())
}

#[tokio::test]
async fn netting_endpoint_returns_positions_and_transfers() {
    let (status, body) = post(
        "/v1/netting",
        json!({
            "cycleId": "2026-10-02-001",
            "settlementCurrency": "USD",
            "fxMode": "PRECOMPUTED",
            "obligations": [
                {"id": 1, "debtor": "USBANK", "creditor": "MXBANK", "currency": "USD", "amount": 10000, "settlementAmount": 10000},
                {"id": 2, "debtor": "MXBANK", "creditor": "USBANK", "currency": "MXN", "amount": 55500, "settlementAmount": 3000}
            ]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        body["transfers"],
        json!([{"from": "USBANK", "to": "MXBANK", "amount": 7000}])
    );
    assert_eq!(body["stats"]["grossSettlementValue"], 13000);
    assert_eq!(body["stats"]["netSettlementValue"], 7000);
    assert_eq!(body["stats"]["planMethod"], "EXACT_DP");
}

#[tokio::test]
async fn netting_endpoint_converts_with_supplied_rates() {
    let (status, body) = post(
        "/v1/netting",
        json!({
            "cycleId": "c",
            "settlementCurrency": "USD",
            "fxMode": "PER_OBLIGATION",
            "rates": [{"currency": "JPY", "rate": "150", "exponent": 0}],
            "obligations": [
                {"id": 1, "debtor": "JP1", "creditor": "AU1", "currency": "JPY", "amount": 15000}
            ]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["positions"][0]["net"], 10000); // AU1 sorts first
}

#[tokio::test]
async fn invalid_input_is_unprocessable() {
    let (status, body) = post(
        "/v1/netting",
        json!({
            "cycleId": "c",
            "settlementCurrency": "USD",
            "obligations": [{"id": 1, "debtor": "A", "creditor": "B", "currency": "CHF", "amount": 10}]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    assert!(
        body["error"]
            .as_str()
            .unwrap()
            .contains("settlement amount missing")
    );
}

#[tokio::test]
async fn lsm_endpoint_releases_gridlock() {
    let (status, body) = post(
        "/v1/lsm/resolve",
        json!({
            "accounts": [
                {"participant": "A", "balance": 0, "limit": 0},
                {"participant": "B", "balance": 0, "limit": 0}
            ],
            "queue": [
                {"id": 10, "debtor": "A", "creditor": "B", "amount": 500},
                {"id": 11, "debtor": "B", "creditor": "A", "amount": 500}
            ]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["released"], json!([10, 11]));
    assert_eq!(body["stats"]["releasedByOffsetting"], 2);
}
