use criterion::{BenchmarkId, Criterion, Throughput, criterion_group, criterion_main};
use netting_engine::fx::FxRate;
use netting_engine::lsm::{self, LsmRequest};
use netting_engine::netting::{self, FxMode, NettingRequest};
use netting_engine::synthetic;
use rust_decimal::Decimal;
use std::hint::black_box;
use std::time::Duration;

const PARTICIPANTS: usize = 40;

fn request(n: usize, mode: FxMode) -> NettingRequest {
    NettingRequest {
        cycle_id: format!("bench-{n}"),
        settlement_currency: "USD".into(),
        settlement_exponent: 2,
        fx_mode: mode,
        rates: synthetic::CURRENCIES
            .iter()
            .enumerate()
            .map(|(i, c)| FxRate {
                currency: (*c).into(),
                rate: Decimal::new(7_000 + 1_337 * i as i64, 3),
                exponent: 2,
            })
            .collect(),
        obligations: synthetic::obligations(n, PARTICIPANTS, 42),
    }
}

fn bench_netting(c: &mut Criterion) {
    let mut group = c.benchmark_group("netting_precomputed_40p");
    group
        .sample_size(10)
        .measurement_time(Duration::from_secs(10));
    for n in [10_000usize, 100_000, 1_000_000] {
        let req = request(n, FxMode::Precomputed);
        group.throughput(Throughput::Elements(n as u64));
        group.bench_with_input(BenchmarkId::from_parameter(n), &req, |b, r| {
            b.iter(|| netting::net(black_box(r)).unwrap())
        });
    }
    group.finish();

    let mut group = c.benchmark_group("netting_fx_modes_40p_100k");
    group
        .sample_size(10)
        .measurement_time(Duration::from_secs(10));
    for mode in [FxMode::PerObligation, FxMode::NetThenConvert] {
        let req = request(100_000, mode);
        group.throughput(Throughput::Elements(100_000));
        group.bench_with_input(
            BenchmarkId::from_parameter(format!("{mode:?}")),
            &req,
            |b, r| b.iter(|| netting::net(black_box(r)).unwrap()),
        );
    }
    group.finish();

    let mut group = c.benchmark_group("netting_json_decode_and_net_40p");
    group
        .sample_size(10)
        .measurement_time(Duration::from_secs(10));
    for n in [100_000usize, 1_000_000] {
        let body = serde_json::to_vec(&request(n, FxMode::Precomputed)).unwrap();
        group.throughput(Throughput::Elements(n as u64));
        group.bench_with_input(BenchmarkId::from_parameter(n), &body, |b, body| {
            b.iter(|| {
                let req: NettingRequest = serde_json::from_slice(black_box(body)).unwrap();
                netting::net(&req).unwrap()
            })
        });
    }
    group.finish();
}

fn bench_lsm(c: &mut Criterion) {
    let mut group = c.benchmark_group("lsm_resolve_40p");
    group
        .sample_size(10)
        .measurement_time(Duration::from_secs(5));
    for n in [1_000usize, 10_000, 100_000] {
        let (accounts, queue) = synthetic::lsm_workload(n, PARTICIPANTS, 7);
        let req = LsmRequest { accounts, queue };
        group.throughput(Throughput::Elements(n as u64));
        group.bench_with_input(BenchmarkId::from_parameter(n), &req, |b, r| {
            b.iter(|| lsm::resolve(black_box(r)).unwrap())
        });
    }
    group.finish();
}

criterion_group!(benches, bench_netting, bench_lsm);
criterion_main!(benches);
