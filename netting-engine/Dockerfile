FROM rust:1-slim AS build
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY src src
COPY benches benches
RUN cargo build --release --locked --bin netting-engine

FROM debian:bookworm-slim
COPY --from=build /src/target/release/netting-engine /usr/local/bin/netting-engine
ENV BIND_ADDR=0.0.0.0:7070 RUST_LOG=info
EXPOSE 7070
USER 65534
ENTRYPOINT ["/usr/local/bin/netting-engine"]
