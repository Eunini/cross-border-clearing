# Design notes

This document records the main decisions in `cross-border-clearing`, the
alternatives that were considered, and the trade-offs. The system is a
simulation: participants, rates and settlement are synthetic. The decisions
are still made as if the system had to be correct under concurrency,
retries and partial failure.

## 1. Shape of the system

```
participant banks --pacs.008--> clearing-gateway (Java) --pacs.002--> participant banks
                                    |  PostgreSQL (payments, positions, cycles, outbox)
                                    |  HTTP/JSON
                                    v
                               netting-engine (Rust)
```

* The **gateway** owns all state: payments, history, participant positions,
  clearing cycles, the outbox. It is the only writer to PostgreSQL.
* The **engine** is stateless. Each call carries its full input (a cycle's
  obligations, or a queue snapshot with balances and limits) and returns a
  result that depends only on that input. That one property drives most of
  the failure handling (section 8).

## 2. ISO 20022 choices

* **Message versions.** pacs.008.001.08, pacs.002.001.10 and camt.053.001.08:
  the versions used by the SWIFT CBPR+ and most RTGS ISO 20022 migrations.
  The XSDs are the official ones from iso20022.org; JAXB classes are
  generated from them at build time (`iso20022-model`), so the Java model
  cannot drift from the schema.
* **Validation in two layers.** XSD validation while unmarshalling catches
  structure, patterns (BIC, UUIDv4, currency code) and cardinality; a failing
  message gets a group-level `RJCT` with `FF01`. Business rules (rulebook)
  run per transaction and produce per-transaction `RJCT` with specific
  ExternalStatusReason codes (`AC02`/`AC03` accounts, `AM03` currency, `AM12`
  decimals, `AM02` limit, `AM05` duplicates, `RC03`/`RC04` agents, `DT01`
  date, `AG03` unsupported, `AB01` queue timeout).
* **Outbound messages are schema-validated too.** The marshaller has the XSD
  attached; a coding error raises an exception instead of putting an invalid
  pacs.002 or camt.053 on the wire (there is a test for this).
* **XML hardening.** DTDs and external entities are disabled (XXE test
  included), message size is bounded, and transactions per message are capped.
* **Status semantics.** An accepted payment is reported `ACSP`
  (AcceptedSettlementInProcess), not `ACSC`. Acceptance means the debtor
  agent's position was debited under its cap, so settlement is guaranteed and
  the creditor agent is told to credit its customer immediately, but the
  interbank settlement happens at the cycle's end. `ACSC` appears in the
  tracker once the cycle settles. Queued payments are `PDNG`.
* **Account formats.** IBAN with MOD 97-10 for GB, DE, FR, NL, CH, BR;
  18-digit CLABE with its 3-7-1 check digit for MX; digit-length rules for the
  other countries. These are simplified stand-ins (no national bank-code
  registries).

## 3. Payment lifecycle and exactly-once processing

```
RECEIVED -> VALIDATED -> FX_QUOTED -> ACCEPTED -> CLEARED -> SETTLED
                              \-> QUEUED --/
RECEIVED | VALIDATED | FX_QUOTED | QUEUED -> REJECTED
```

Transitions are enforced in one place (`PaymentState`, `Lifecycle`); bulk
transitions at cycle time are SQL updates guarded by `WHERE state = ...`, so
they are idempotent. Every transition is stored in `payment_event`, which is
what the gpi-style tracker (`GET /payments/{uetr}/status`) returns.

**Idempotency keys, from coarse to fine:**

| Key | Scope | Retransmission with same content | Same key, different content |
|---|---|---|---|
| (InstgAgt, MsgId) | message | stored pacs.002 returned byte-for-byte | group `RJCT AM05` |
| UETR | transaction | stored outcome replayed, no side effects | transaction `RJCT AM05`, original untouched |
| (DbtrAgt, EndToEndId) | business reference | n/a | `RJCT AM05` |

The UETR is claimed with `INSERT ... ON CONFLICT (uetr) DO NOTHING` in the
same database transaction as every side effect (position change, history,
outbox event). A crash therefore leaves either nothing (the retry processes
the payment) or everything (the retry replays it). The test
`concurrentRetriesOfOnePaymentAreProcessedExactlyOnce` fires 32 concurrent
submissions of one UETR and checks there is exactly one payment, one debit
and four history rows.

The EndToEndId is claimed last, so a payment rejected for another reason
does not burn the reference; a payment rejected `AB01` from the queue
releases it.

## 4. Throughput: the hot-row problem and the sequencer

Every payment changes two participant position rows, and a few large banks
appear in most payments. The first implementation used one transaction per
payment: lock both rows, check the cap, update, write history and outbox,
commit. Measured on the test VPS that topped out at about **300 payments/s
with a p99 of 1.6 s**, because each hot row was locked across several round
trips plus the commit's fsync, and everything queued behind it.

The current design separates the work:

1. **Request threads** (virtual threads) do everything that needs no shared
   state: XML parsing and schema validation, rulebook checks, FX quote.
2. A **single-writer sequencer** drains whatever has queued (up to 500) and
   processes it as one transaction: claim UETRs, claim EndToEndIds, lock the
   involved position rows once (ascending id order), decide each payment in
   arrival order in memory (`PositionBook`), then write states, positions,
   history and outbox rows with set-based statements (`unnest` arrays).

This is application-level group commit, the same idea as an exchange's
sequencer or the LMAX single-writer pattern. Decisions stay strictly
sequential (FIFO per debtor is trivially preserved), lock acquisition and the
commit are amortised over the batch, and batch size adapts to load (at low
load a batch has one payment, so latency stays low). The cost is that one bad
batch affects up to 500 requests; the batch is retried on transient
database errors, and a failure is reported to the callers, who can safely
retry because of UETR idempotency.

Alternatives considered: sharding each bank's cap into sub-accounts (escrow)
removes contention but fragments headroom and makes cap checks approximate;
an in-memory risk engine with asynchronous persistence would be faster still
but makes "accepted" mean "accepted in memory", which is the wrong default
for a payment system.

## 5. FX

* Mid rates are simulated per currency (units per USD) with a seeded
  geometric random walk; HKD is clamped to its 7.75-7.85 band. Each currency
  has a fixed spread.
* A quote converts the instructed amount into the settlement currency at the
  mid widened by half the source spread, and from there into the creditor's
  currency at the mid narrowed by half the target spread. Each leg is rounded
  half-to-even to the minor unit of its currency (JPY has none).
* The quote is fixed at acceptance and its settlement value is the
  obligation that gets netted. A queued payment whose quote expires is
  rejected `AB01` rather than silently re-priced.

## 6. Multilateral netting (Rust)

**Input:** `n` obligations (debtor, creditor, currency, amount, settlement
value). **Output:** per-participant net positions (per currency and in the
settlement currency) and a list of settlement transfers.

1. **Aggregation** is a single pass with dense integer ids: three hash
   lookups per obligation (participant and currency interning), checked
   integer arithmetic, then a renumbering into lexicographic order so the
   output does not depend on input order. Duplicate ids are detected with a
   bitmap when ids are dense (database sequences) and a hash set otherwise.
   `O(n + k log k)`.
2. **FX modes.** `PRECOMPUTED` uses the quoted settlement value (what the
   gateway uses). `PER_OBLIGATION` converts each obligation at cycle rates.
   `NET_THEN_CONVERT` nets per currency and converts each net once; naive
   rounding would break `sum(nets) == 0`, so the rounding residual is
   apportioned with the largest-remainder method: each result is within one
   minor unit of its exact value and the results sum exactly to zero.
3. **Settlement transfers.** Any plan that only moves money from net payers
   to net receivers transfers exactly `sum(max(net, 0))`, which is the minimum
   possible value (a min-cost-flow formulation with no intermediaries has
   this as its optimum). Minimising the *number* of transfers is NP-hard:
   with `k` non-zero positions the optimum is `k - g`, where `g` is the
   largest number of disjoint zero-sum groups. The engine:
   * for `k <= 20`, finds `g` exactly with an `O(2^k * k)` subset DP and
     reconstructs the groups (provably optimal);
   * above that, matches exact payer/receiver pairs first, then settles the
     rest greedily largest-to-largest (at most `m - 1` transfers for `m`
     remaining), and reports the lower bound `max(#payers, #receivers)` so the
     gap is visible.
4. **Reconciliation in Java.** Before anything is persisted, the gateway
   recomputes every net from the obligations, requires exact agreement, a
   zero sum, and transfers that reproduce each net. A mismatch marks the cycle
   `FAILED` for an operator instead of settling something wrong (tested with
   a deliberately corrupted engine response).

## 7. Liquidity-saving mechanism (gridlock resolution)

A payment that would push the debtor below `-cap`, or whose debtor already
has queued payments (FIFO), is queued instead of rejected. Queues can
gridlock: A waits for B, B for C, C for A, although all three could settle
together.

The engine implements the iterative-removal algorithm used in RTGS
liquidity-saving mechanisms (Bech and Soramaki, 2001):

1. tentatively settle the whole queue at once;
2. while some participant is below its floor
   `min(current balance, -cap)`, drop that participant's lowest-priority
   (last) included payment;
3. settle what remains, atomically.

**Soundness:** the loop stops only when nobody is below its floor. The empty
set is always feasible, so a violator always has a payment left to drop.

**Optimality (with FIFO):** for any feasible set `T` that respects each
debtor's queue order, the invariant `T ⊆ S` holds: if a violator's prefix in
`S` equalled its prefix in `T`, its outflows would be equal and its inflows
at least as large, so it could not be violating. Hence the result contains
every feasible FIFO-respecting set; it is the unique maximum. Without the
FIFO constraint the problem is NP-hard. Complexity `O(n + k)` with a
worklist. Property tests compare the result with brute force on small
instances, and the Java contract test checks that the Rust engine and a
naive Java implementation release identical sets.

The gateway runs the LSM every 500 ms (and before every cut-off) on a
snapshot. Before applying a result it locks the positions and re-checks the
released set against the current positions with the same floor rule; a
stale result is discarded and the next run retries.

## 8. Failure handling between Java and Rust

* All engine calls are pure, so retries are safe: bounded retries with
  exponential backoff for I/O errors and 5xx; 4xx (invalid input) is not
  retried.
* **Netting unavailable:** the cycle stays `CLOSING`; payments keep flowing
  into the next cycle; the cycle job retries and cycles are netted and
  settled in order (tested by switching the engine off).
* **LSM unavailable:** payments simply stay queued; the only effect is delay,
  bounded by quote expiry (`AB01`).
* **Wrong answer:** caught by reconciliation (section 6).
* Every cycle step (`OPEN -> CLOSING -> NETTED -> SETTLED`) is a guarded SQL
  status transition, so re-running a step after a crash is a no-op.

## 9. Transactional outbox

Events (payment accepted, rejected, queued; cycle netted; statement
available) are inserted in the same transaction as the state change. A
publisher claims batches with `FOR UPDATE SKIP LOCKED` (several publishers
can run), delivers them, and marks them published. Delivery is
at-least-once; the sink (a simulated participant inbox table) deduplicates on
the event id. Swapping the sink for Kafka or participant webhooks changes one
method.

## 10. Locking order

Every writer takes locks in the same order: open cycle row (`FOR SHARE` for
payment processing and LSM, `FOR UPDATE` for cut-off), then position rows in
ascending participant id, then payment rows. Cut-off therefore waits for
in-flight batches, and the sequencer, LSM and settlement cannot deadlock.

## 11. Why Rust for the engine

* The netting and LSM paths are CPU-bound, allocation-sensitive loops over
  millions of records: predictable latency without GC pauses matters at
  cycle cut-off, when the whole network waits for the result.
* Integer money with checked arithmetic and no implicit numeric widening
  makes overflow an explicit error rather than a silent wrap.
* `proptest` makes the invariants (conservation, transfers reproduce nets,
  limits never violated, FIFO maximality) cheap to state and test.
* Keeping it a separate stateless service gives a clean failure boundary and
  lets it scale independently. The cost is a serialisation hop: for very
  large cycles JSON decoding dominates (see the README benchmarks), and a
  binary encoding (protobuf/gRPC or Arrow) would be the next step.

## 12. Known limitations

* Simulated participants, rates and settlement agent; not connected to any
  network or central bank.
* Admin endpoints are unauthenticated; there is no mTLS or message signing.
* One gateway instance: the sequencer and cycle jobs assume a single writer.
  Running several instances would need leader election (e.g. a PostgreSQL
  advisory lock) for those components.
* Settlement is a single settlement-currency leg per participant; there is no
  payment-versus-payment settlement of the FX legs.
