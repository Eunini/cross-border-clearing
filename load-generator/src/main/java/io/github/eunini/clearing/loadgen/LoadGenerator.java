package io.github.eunini.clearing.loadgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.eunini.clearing.iso.sample.Pacs008Writer;
import io.github.eunini.clearing.iso.sample.SampleAccounts;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives synthetic cross-border traffic through the gateway's ISO 20022
 * endpoint and measures it end to end.
 *
 * <pre>
 * java -jar load-generator.jar --url=http://localhost:8080 --payments=100000 --concurrency=64 \
 *      --batch=1 --cycles=5 --cap-scale=1.0 --out=results/run.json
 * </pre>
 *
 * Latency is measured per HTTP exchange: from sending the pacs.008 to
 * receiving the complete pacs.002 (validation, FX, risk check and durable
 * commit included). Throughput counts credit transfers (not messages) per
 * second of wall-clock time.
 */
public final class LoadGenerator {

    private static final Pattern TX_STS = Pattern.compile("<TxSts>([A-Z]{4})</TxSts>");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    record Bank(String bic, String country, String currency) {}

    private final Map<String, String> args;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String url;

    LoadGenerator(Map<String, String> args) {
        this.args = args;
        this.url = args.getOrDefault("url", "http://localhost:8080");
    }

    public static void main(String[] argv) throws Exception {
        Map<String, String> args = new HashMap<>();
        for (String a : argv) {
            if (a.startsWith("--") && a.contains("=")) {
                args.put(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
            } else {
                System.err.println("Ignoring argument " + a + " (use --key=value)");
            }
        }
        new LoadGenerator(args).run();
    }

    int intArg(String k, int d) {
        return Integer.parseInt(args.getOrDefault(k, Integer.toString(d)));
    }

    void run() throws Exception {
        int payments = intArg("payments", 20_000);
        int concurrency = intArg("concurrency", 64);
        int batch = intArg("batch", 1);
        int cycles = intArg("cycles", 4);
        int warmup = intArg("warmup", 2_000);
        long seed = Long.parseLong(args.getOrDefault("seed", "2026"));
        double capScale = Double.parseDouble(args.getOrDefault("cap-scale", "1.0"));
        Map<String, Double> mix = Map.of(
                "retail", Double.parseDouble(args.getOrDefault("retail", "0.70")),
                "sme", Double.parseDouble(args.getOrDefault("sme", "0.25")));

        List<Bank> banks = new ArrayList<>();
        for (JsonNode p : get("/api/participants")) {
            banks.add(new Bank(p.get("bic").asText(), p.get("country").asText(), p.get("currency").asText()));
        }
        Map<String, BigDecimal> mids = new HashMap<>();
        get("/api/fx/rates").fields().forEachRemaining(e -> mids.put(e.getKey(), e.getValue().get("mid").decimalValue()));
        Map<String, Integer> exponents = Map.of("JPY", 0);
        Map<String, List<Bank>> byCountry = new LinkedHashMap<>();
        banks.forEach(b -> byCountry.computeIfAbsent(b.country(), k -> new ArrayList<>()).add(b));
        Corridors corridors = new Corridors(Corridors.DEFAULT, new ArrayList<>(byCountry.keySet()));

        post("/api/admin/participants/caps/scale?reset=true&factor=" + capScale);
        System.out.printf("Participants: %d in %d countries; caps scaled x%s%n", banks.size(), byCountry.size(), capScale);

        // Warm-up (JIT, connection pools), then cut off so measured cycles contain only measured traffic.
        if (warmup > 0) {
            System.out.printf("Warm-up: %d payments%n", warmup);
            drive(warmup, concurrency, batch, new SplittableRandom(seed ^ 0xFFFF), corridors, byCountry, mids, exponents, mix);
            post("/api/admin/cycles/close");
        }
        JsonNode lsmBefore = get("/api/dashboard").get("lsm");

        List<JsonNode> cycleResults = new ArrayList<>();
        SplittableRandom rng = new SplittableRandom(seed);
        Stats total = new Stats();
        int perCycle = (int) Math.ceil(payments / (double) cycles);
        Instant startedAt = Instant.now();
        long started = System.nanoTime();
        for (int c = 0; c < cycles; c++) {
            int n = Math.min(perCycle, payments - c * perCycle);
            Stats s = drive(n, concurrency, batch, rng, corridors, byCountry, mids, exponents, mix);
            total.merge(s);
            long closeStart = System.nanoTime();
            JsonNode cycle = post("/api/admin/cycles/close");
            long closeMs = (System.nanoTime() - closeStart) / 1_000_000;
            ((com.fasterxml.jackson.databind.node.ObjectNode) cycle).put("cutOffToSettledMs", closeMs);
            cycleResults.add(cycle);
            System.out.printf("Cycle %d: %d tx in %.1fs (%.0f tx/s), p50 %.1f ms, p99 %.1f ms | gross %s net %s saved %s%% | transfers %s | close+net+settle %d ms%n",
                    cycle.get("id").asLong(), s.txs.sum(), s.seconds(), s.txs.sum() / s.seconds(), s.percentile(50),
                    s.percentile(99), money(cycle.get("grossValue")), money(cycle.get("netValue")),
                    cycle.get("liquiditySavingPct").asText(), cycle.get("transferCount").asText(), closeMs);
        }
        double wall = (System.nanoTime() - started) / 1e9;
        JsonNode lsmAfter = get("/api/dashboard").get("lsm");
        JsonNode live = get("/api/dashboard").get("live");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", startedAt.toString());
        report.put("completedAt", Instant.now().toString());
        report.put("config", Map.of("payments", payments, "concurrency", concurrency, "batch", batch,
                "cycles", cycles, "warmup", warmup, "seed", seed, "capScale", capScale, "mix", mix));
        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("transactions", total.txs.sum());
        gateway.put("messages", total.messages.sum());
        gateway.put("sendingSeconds", round(total.nanos.get() / 1e9));
        gateway.put("throughputTxPerSec", round(total.txs.sum() / (total.nanos.get() / 1e9)));
        gateway.put("latencyMs", Map.of("p50", total.percentile(50), "p90", total.percentile(90),
                "p99", total.percentile(99), "p999", total.percentile(99.9), "max", total.percentile(100)));
        gateway.put("statuses", total.statuses());
        gateway.put("httpErrors", total.errors.sum());
        report.put("gateway", gateway);
        long gross = 0, net = 0, transfers = 0, count = 0;
        for (JsonNode c : cycleResults) {
            gross += c.get("grossValue").asLong();
            net += c.get("netValue").asLong();
            transfers += c.get("transferCount").asLong();
            count += c.get("paymentCount").asLong();
        }
        Map<String, Object> netting = new LinkedHashMap<>();
        netting.put("paymentsNetted", count);
        netting.put("grossSettlementUsd", gross / 100.0);
        netting.put("netSettlementUsd", net / 100.0);
        netting.put("liquiditySavingPct", gross == 0 ? 0 : round(100.0 * (1 - (double) net / gross)));
        netting.put("settlementTransfers", transfers);
        netting.put("transferReductionPct", count == 0 ? 0 : round(100.0 * (1 - (double) transfers / count)));
        netting.put("cycles", cycleResults);
        report.put("netting", netting);
        report.put("lsm", Map.of(
                "released", lsmAfter.get("released").asLong() - lsmBefore.get("released").asLong(),
                "releasedByOffsetting", lsmAfter.get("released_by_offsetting").asLong()
                        - lsmBefore.get("released_by_offsetting").asLong(),
                "stillQueuedAtEnd", live.get("queued").asLong()));
        report.put("wallSeconds", round(wall));

        String json = JSON.writeValueAsString(report);
        System.out.println(json);
        if (args.containsKey("out")) {
            Path out = Path.of(args.get("out"));
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, json);
            System.out.println("Wrote " + out);
        }
    }

    /** Sends {@code n} credit transfers in messages of {@code batch}, at most {@code concurrency} in flight. */
    Stats drive(int n, int concurrency, int batch, SplittableRandom rng, Corridors corridors,
                Map<String, List<Bank>> byCountry, Map<String, BigDecimal> mids, Map<String, Integer> exponents,
                Map<String, Double> mix) throws InterruptedException {
        Stats stats = new Stats();
        Semaphore inFlight = new Semaphore(concurrency);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long start = System.nanoTime();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            int sent = 0;
            while (sent < n) {
                // Messages carry one debtor agent; pick it from the first transaction's corridor.
                String[] pair = corridors.pick(rng);
                Bank debtor = pickBank(byCountry.get(pair[0]), rng);
                int size = Math.min(batch, n - sent);
                List<Pacs008Writer.Tx> txs = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    String creditorCountry = i == 0 ? pair[1] : creditorCountryFor(debtor.country(), corridors, rng, pair[1]);
                    Bank creditor = pickBank(byCountry.get(creditorCountry), rng);
                    txs.add(tx(debtor, creditor, rng, mids, exponents, mix));
                }
                String xml = Pacs008Writer.write(new Pacs008Writer.Message(
                        "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 30), Instant.now(),
                        debtor.bic(), null, today, true, txs));
                sent += size;
                inFlight.acquire();
                exec.submit(() -> {
                    try {
                        send(xml, stats);
                    } finally {
                        inFlight.release();
                    }
                });
            }
        }
        stats.nanos.addAndGet(System.nanoTime() - start);
        return stats;
    }

    /** Samples the corridor model until it yields a flow out of {@code debtorCountry}. */
    static String creditorCountryFor(String debtorCountry, Corridors corridors, SplittableRandom rng, String fallback) {
        for (int attempt = 0; attempt < 32; attempt++) {
            String[] p = corridors.pick(rng);
            if (p[0].equals(debtorCountry)) {
                return p[1];
            }
        }
        return fallback;
    }

    /** Larger banks (first in their country) carry more traffic: weights 1, 1/2, 1/3, ... */
    static Bank pickBank(List<Bank> banks, SplittableRandom rng) {
        double total = 0;
        for (int i = 0; i < banks.size(); i++) {
            total += 1.0 / (i + 1);
        }
        double x = rng.nextDouble() * total;
        for (int i = 0; i < banks.size(); i++) {
            x -= 1.0 / (i + 1);
            if (x <= 0) {
                return banks.get(i);
            }
        }
        return banks.getLast();
    }

    static Pacs008Writer.Tx tx(Bank from, Bank to, SplittableRandom rng, Map<String, BigDecimal> mids,
                               Map<String, Integer> exponents, Map<String, Double> mix) {
        double usd = Corridors.usdAmount(rng, mix);
        int exp = exponents.getOrDefault(from.currency(), 2);
        BigDecimal amount = BigDecimal.valueOf(usd).multiply(mids.get(from.currency()))
                .setScale(exp, RoundingMode.HALF_EVEN);
        if (amount.signum() <= 0) {
            amount = BigDecimal.ONE.setScale(exp);
        }
        String ref = Long.toString(rng.nextLong() & Long.MAX_VALUE, 36);
        return new Pacs008Writer.Tx(UUID.randomUUID().toString(), "E2E" + ref, "TX" + ref, from.bic(), to.bic(),
                "Customer " + ref.substring(0, Math.min(6, ref.length())),
                SampleAccounts.forParticipant(from.country(), from.bic(), rng),
                "Beneficiary " + ref.substring(0, Math.min(6, ref.length())),
                SampleAccounts.forParticipant(to.country(), to.bic(), rng), from.currency(), amount);
    }

    void send(String xml, Stats stats) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/iso20022/pacs.008"))
                .header("Content-Type", "application/xml").timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(xml)).build();
        long t0 = System.nanoTime();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long micros = (System.nanoTime() - t0) / 1_000;
            if (resp.statusCode() != 200) {
                stats.errors.increment();
                return;
            }
            stats.latenciesMicros.add(micros);
            stats.messages.increment();
            Matcher m = TX_STS.matcher(resp.body());
            while (m.find()) {
                stats.txs.increment();
                stats.status(m.group(1));
            }
        } catch (IOException e) {
            stats.errors.increment();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    JsonNode get(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(r.body());
    }

    JsonNode post(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url + path))
                .timeout(Duration.ofMinutes(10)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException(path + " -> HTTP " + r.statusCode() + ": " + r.body());
        }
        return JSON.readTree(r.body());
    }

    static String money(JsonNode minor) {
        return String.format("%,.0f", minor.asLong() / 100.0);
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** Thread-safe run statistics. */
    static final class Stats {
        final ConcurrentLinkedQueue<Long> latenciesMicros = new ConcurrentLinkedQueue<>();
        final LongAdder txs = new LongAdder();
        final LongAdder messages = new LongAdder();
        final LongAdder errors = new LongAdder();
        final AtomicLong nanos = new AtomicLong();
        final Map<String, LongAdder> byStatus = new java.util.concurrent.ConcurrentHashMap<>();
        private long[] sorted;

        void status(String s) {
            byStatus.computeIfAbsent(s, k -> new LongAdder()).increment();
        }

        Map<String, Long> statuses() {
            Map<String, Long> m = new java.util.TreeMap<>();
            byStatus.forEach((k, v) -> m.put(k, v.sum()));
            return m;
        }

        void merge(Stats o) {
            latenciesMicros.addAll(o.latenciesMicros);
            txs.add(o.txs.sum());
            messages.add(o.messages.sum());
            errors.add(o.errors.sum());
            nanos.addAndGet(o.nanos.get());
            o.byStatus.forEach((k, v) -> byStatus.computeIfAbsent(k, x -> new LongAdder()).add(v.sum()));
            sorted = null;
        }

        double seconds() {
            return nanos.get() / 1e9;
        }

        double percentile(double p) {
            if (sorted == null || sorted.length != latenciesMicros.size()) {
                sorted = latenciesMicros.stream().mapToLong(Long::longValue).toArray();
                Arrays.sort(sorted);
            }
            if (sorted.length == 0) {
                return 0;
            }
            int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
            return round(sorted[Math.max(0, Math.min(sorted.length - 1, idx))] / 1000.0);
        }
    }
}
