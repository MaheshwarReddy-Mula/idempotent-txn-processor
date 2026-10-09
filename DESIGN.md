# Design Note

## 1. Problem and approach

Requests arrive from an upstream system at-least-once, in any order, sometimes malformed, sometimes while a
downstream dependency is failing. The service must still update balances **exactly once per business
transaction** and be able to explain what happened to every record. I optimised for correctness of the
core behaviour over breadth of features, and kept every infrastructure concern behind an interface.

## 2. Architecture

```
 TransactionSource ──► TransactionProcessor ──► TransactionStore
 (CSV / in-memory;      │  validate                (idempotency index, transaction state,
  queue/API later)      │  dedupe                   ledger + balances, atomic commit)
                        │  sequence gate
                        │  retry loop ◄── FailureInjector (simulation seam)
                        ▼
              ProcessingEventLog + ProcessingReport
```

* **Business rules vs infrastructure**: `TransactionProcessor` knows nothing about files or databases; it talks to
  `TransactionSource` and `TransactionStore` interfaces. Swapping CSV for a Kafka/SQS consumer, or the in-memory
  store for JDBC, does not touch the processing logic (L2-FR-01, FR-10).
* **Raw records**: the source yields `RawRecord`s (strings). Parsing/validation happens in the core so one bad
  line is an isolated `REJECTED` outcome instead of an exception that kills the batch.

## 3. Status lifecycle

```
RECEIVED ─► PENDING_SEQUENCE ─► PROCESSING ─► PROCESSED
                                   │   ▲
                                   ▼   │
                              RETRY_PENDING ─► (max attempts) FAILED
PROCESSING ─► FAILED          non-retryable business failure (e.g. INSUFFICIENT_FUNDS)
RECEIVED   ─► REJECTED        invalid input or conflicting request
RECEIVED   ─► DUPLICATE       repeat of a known transaction, no side effects
```

I added `PENDING_SEQUENCE` (buffered out-of-order record) and `REJECTED` (never processed) to the statuses in
the brief so that "invalid" and "failed during processing" stay distinguishable in reports. Every input record
gets exactly one outcome, so summary counts always add up to the input size, and each transaction keeps its full
status history.

## 4. Idempotency design

Two independent layers:

1. **Request-level (processor)**: `requestId` (idempotency key) and `transactionId` (business identity) are
   indexed. A repeat is classified by comparing a *business fingerprint* (account, type, amount, currency,
   sequence, reference; `requestId` excluded):
   * same fingerprint → `DUPLICATE`, nothing executed;
   * different fingerprint with a known id → `REJECTED` as a conflict (a retry must never silently change money);
   * `requestId` reused for a *different* transaction → `REJECTED`.
2. **Effect-level (store)**: `commit()` is a no-op (`ALREADY_APPLIED`) if the ledger already has an entry for the
   transactionId. This is the real safety net: even if layer 1 were bypassed (crash, replay after restart,
   a second consumer), the money cannot move twice. In a database this is a `UNIQUE(transaction_id)` constraint
   on the ledger table.

## 5. Duplicate detection logic

Duplicates are detected regardless of *when* they arrive: after the original is processed, while it is still
waiting in the sequence buffer, or after it failed (a duplicate of a `FAILED` transaction is not reprocessed;
reprocessing would be an explicit operator action). A duplicate's `requestId` is also remembered, so a later
replay of that key is recognised directly.

## 6. Out-of-order strategy: sequence-based (Option A)

* Records are applied strictly in sequence order. A record whose turn has not come is buffered
  (`PENDING_SEQUENCE`) and released as soon as every earlier sequence number is resolved.
* A slot is *resolved* when its record is `PROCESSED`, terminally `FAILED`, or was rejected as invalid
  (its parseable sequence number is released). So one bad record never blocks the stream.
* Slots still missing at the end are **reported** (`Missing sequence numbers: [7]`) and the records behind them
  stay `PENDING_SEQUENCE`, not silently dropped.

**Why this one:** balances are order-dependent (debit before credit overdraws), and the supplied data carries
sequence numbers. The sample data implies one global sequence across accounts, so the gate is global.

**Trade-offs / assumptions:** a single global sequence is a bottleneck; production would use one sequence per
partition key (e.g. per account) so accounts progress independently. A permanently lost sequence number blocks
everything behind it, so production needs a gap timeout → dead-letter/escalation. A record that fails
terminally consumes its slot rather than blocking the pipeline; the alternative (halt on failure) is safer for
strictly dependent flows and is a business decision.

## 7. Retry and failure handling

* **Transient failures** (`TransientProcessingException`) are retried up to `maxAttempts` with exponential
  backoff; each retry is visible in the status history (`RETRY_PENDING`).
* **Two simulated failure points** to prove retry safety:
  * *before commit*: nothing was applied, retry applies once;
  * *after commit* (lost acknowledgement): the effect **was** applied, the retry hits the idempotent commit and
    returns `ALREADY_APPLIED`, so balance changes once. This is the case naive retry logic gets wrong.
* **Retries exhausted** → `FAILED` (`RETRIES_EXHAUSTED`), traceable in the report/log with attempt count.
* **Non-retryable business failures** (insufficient funds, reversal of an unknown/already reversed transaction)
  → `FAILED` immediately; retrying cannot help.
* **Unexpected exceptions** → `FAILED (UNEXPECTED_ERROR)`; only the exception type is logged (messages can carry
  sensitive data).

## 8. Data consistency

`commit()` decides the full effect and checks every rule **before** mutating anything, then applies balance +
ledger entry + reversal marker together under one lock. A failure can therefore never leave a half-applied
transaction. Equivalent in a database: one local transaction around the three writes.

## 9. Observability and security

* Structured `key=value` events (`RECEIVED`, `PENDING_SEQUENCE`, `PROCESSING`, `RETRY_SCHEDULED`, `PROCESSED`,
  `DUPLICATE`, `REJECTED`, `FAILED`) with timestamp, transactionId, requestId, attempt: ready for ELK/Splunk.
* Processing report: counts by status, per-record outcome and detail, lifecycle history, retries, duplicates,
  missing sequences, balances.
* Account ids are masked in logs/reports, amounts are never logged, raw input values are sanitised before
  logging (no log injection), validation messages never echo input values, ids are format-restricted, there are
  no secrets in code, and the `commit` path uses fixed error codes.

## 10. AI-assisted development usage

> **Edit this section to reflect how you actually worked. You must be able to explain it in the review.**

* AI was used to scaffold the project structure, draft the processor/store/validator, and generate the first
  version of the test suite.
* I reviewed the generated design against the brief and decided the key choices myself: sequence-based
  ordering, idempotency at both request and effect level, "slot released on invalid record", and simulating the
  lost-acknowledgement case.
* **Validation of generated code:** compiled on JDK 21, ran the unit test suite (23 tests), ran the CSV samples
  end to end and checked outputs against expected balances by hand (e.g. ACC-201: 100 − 40 + 25 = 85), and
  deliberately tested failure paths (retry exhaustion, lost ack, conflicting duplicate, concurrent commits).
* Where AI output was changed or rejected: *(add your own examples here)*.

## 11. Production hardening recommendations

| Area | Recommendation |
|---|---|
| Persistence | JDBC/JPA store; ledger `UNIQUE(transaction_id)`; request/idempotency table with TTL; processor state (next sequence, buffer) persisted so a restart resumes correctly (currently in memory). |
| Concurrency | Single consumer per sequence partition (partition by account); row-level locks or optimistic versioning on balances if multiple workers; outbox pattern for downstream side effects. |
| Queue semantics | At-least-once delivery + idempotent consumer (already the model). Commit offsets only after the outcome is persisted. Dead-letter queue for `FAILED`/`REJECTED`. |
| Ordering | Per-key sequences, gap timeout and alerting, bounded buffer size with backpressure. |
| Retry | Delayed retry queue instead of in-process sleep, jitter, circuit breaker, separate poison-message handling. |
| Monitoring | Metrics (processed/duplicate/failed/retried counters, buffer depth, oldest pending age, retry rate), alerts on stuck sequences and DLQ growth, correlation ids / distributed tracing. |
| Recovery | Admin operation to replay a `FAILED` transaction or re-drive a DLQ; reconciliation job comparing ledger to balances. |
| Security | Authn/z on any API ingress, secrets in a vault, PII classification and field-level encryption, audit trail for manual interventions. |
| Testing | Property-based tests (random order + duplicates ⇒ same final state), load/soak tests, chaos tests for crashes between commit and acknowledgement. |
