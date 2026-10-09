package com.example.txnprocessor.processing;

import com.example.txnprocessor.model.InputResult;
import com.example.txnprocessor.model.RawRecord;
import com.example.txnprocessor.model.TransactionRecord;
import com.example.txnprocessor.model.TransactionRequest;
import com.example.txnprocessor.model.TransactionStatus;
import com.example.txnprocessor.store.BusinessRuleException;
import com.example.txnprocessor.store.CommitResult;
import com.example.txnprocessor.store.TransactionStore;
import com.example.txnprocessor.validation.TransactionValidator;
import com.example.txnprocessor.validation.ValidationResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.example.txnprocessor.model.TransactionStatus.*;

/**
 * Core business logic: idempotent, order-tolerant, retry-safe processing of transaction requests.
 *
 * <h3>Ordering strategy: sequence-based (Option A)</h3>
 * Records are applied strictly in sequence order (1, 2, 3, ...). A record whose turn has not come yet is
 * buffered as PENDING_SEQUENCE and released as soon as every earlier sequence number is resolved.
 * A sequence slot is "resolved" when its record is PROCESSED, terminally FAILED, or was REJECTED as invalid,
 * so one bad record can never block the stream forever. Slots that are still missing at the end are reported.
 *
 * <h3>Idempotency</h3>
 * Two keys are tracked: requestId (idempotency key / replay detection) and transactionId (business identity).
 * A repeat with the same payload is a DUPLICATE (no side effects). A repeat with a different payload is a
 * conflict and is REJECTED. Independently, {@link TransactionStore#commit} is a no-op for an already applied
 * transactionId, so even a retry after a lost acknowledgement cannot double-apply.
 *
 * <p>Ingestion is serialized (single consumer per sequence stream), which is also how a partitioned queue
 * consumer would behave in production.
 */
public class TransactionProcessor {

    private final TransactionStore store;
    private final TransactionValidator validator;
    private final FailureInjector injector;
    private final RetryPolicy retryPolicy;
    private final Sleeper sleeper;
    private final ProcessingEventLog log;

    private final List<InputResult> results = new ArrayList<>();
    private final TreeMap<Long, TransactionRecord> pendingBySequence = new TreeMap<>();
    private final TreeSet<Long> skippedSequences = new TreeSet<>();
    private long nextExpectedSequence;
    private long retriesScheduled;

    public TransactionProcessor(TransactionStore store, FailureInjector injector,
                                RetryPolicy retryPolicy, ProcessingEventLog log) {
        this(store, new TransactionValidator(), injector, retryPolicy, Sleeper.threadSleep(), log, 1L);
    }

    public TransactionProcessor(TransactionStore store, TransactionValidator validator, FailureInjector injector,
                                RetryPolicy retryPolicy, Sleeper sleeper, ProcessingEventLog log,
                                long initialSequence) {
        this.store = store;
        this.validator = validator;
        this.injector = injector;
        this.retryPolicy = retryPolicy;
        this.sleeper = sleeper;
        this.log = log;
        this.nextExpectedSequence = initialSequence;
    }

    public ProcessingReport processAll(Iterable<RawRecord> records) {
        for (RawRecord raw : records) {
            ingest(raw);
        }
        return buildReport();
    }

    /** Handles exactly one input record. Never throws for bad data: bad records are isolated as REJECTED. */
    public synchronized void ingest(RawRecord raw) {
        log.event("RECEIVED", "order", raw.inputOrder(),
                "txnId", ProcessingEventLog.safe(raw.get(RawRecord.TRANSACTION_ID)),
                "requestId", ProcessingEventLog.safe(raw.get(RawRecord.REQUEST_ID)));

        // 1. validation
        ValidationResult validation = validator.validate(raw);
        if (!validation.valid()) {
            reject(raw, validation.errors());
            releaseSlotOfRejectedRecord(validation.sequenceHint());
            drain();
            return;
        }
        TransactionRequest req = validation.request();

        // 2. idempotency: by requestId first, then by transactionId
        Optional<String> knownByRequest = store.findTransactionIdByRequestId(req.requestId());
        if (knownByRequest.isPresent()) {
            if (!knownByRequest.get().equals(req.transactionId())) {
                reject(raw, List.of("requestId already used by a different transaction"));
                return;
            }
            TransactionRecord original = store.findByTransactionId(req.transactionId()).orElseThrow();
            if (original.request().fingerprint().equals(req.fingerprint())) {
                markDuplicate(raw, req, original);
            } else {
                reject(raw, List.of("conflicting payload for an already known transaction"));
            }
            return;
        }
        Optional<TransactionRecord> knownByTransaction = store.findByTransactionId(req.transactionId());
        if (knownByTransaction.isPresent()) {
            TransactionRecord original = knownByTransaction.get();
            if (original.request().fingerprint().equals(req.fingerprint())) {
                markDuplicate(raw, req, original);
            } else {
                reject(raw, List.of("conflicting payload for an already known transaction"));
            }
            return;
        }

        // 3. ordering
        long seq = req.sequenceNumber();
        if (seq < nextExpectedSequence) {
            reject(raw, List.of("sequence slot already resolved by another record"));
            return;
        }
        if (pendingBySequence.containsKey(seq)) {
            reject(raw, List.of("sequence number already claimed by another pending transaction"));
            return;
        }
        skippedSequences.remove(seq); // a valid record legitimately claims a slot an invalid one released

        InputResult result = new InputResult(raw.inputOrder(), req.transactionId(), req.requestId());
        results.add(result);
        TransactionRecord record = new TransactionRecord(req, result);
        record.transition(RECEIVED, null);
        store.register(record);

        if (seq == nextExpectedSequence) {
            processRecord(record);
            nextExpectedSequence++;
            drain();
        } else {
            record.transition(PENDING_SEQUENCE, "waiting for earlier sequence numbers");
            pendingBySequence.put(seq, record);
            log.event("PENDING_SEQUENCE", "txnId", req.transactionId(), "sequence", seq,
                    "expected", nextExpectedSequence);
        }
    }

    public synchronized ProcessingReport buildReport() {
        List<ProcessingReport.Row> rows = new ArrayList<>();
        Map<TransactionStatus, Long> counts = new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus s : TransactionStatus.values()) {
            counts.put(s, 0L);
        }
        for (InputResult r : results) {
            rows.add(new ProcessingReport.Row(r.inputOrder(), r.transactionId(), r.requestId(), r.status(),
                    r.attempts(), r.reason(), List.copyOf(r.history())));
            counts.merge(r.status(), 1L, Long::sum);
        }
        List<Long> missing = new ArrayList<>();
        if (!pendingBySequence.isEmpty()) {
            long highest = pendingBySequence.lastKey();
            for (long s = nextExpectedSequence; s < highest && missing.size() < 1000; s++) {
                if (!pendingBySequence.containsKey(s) && !skippedSequences.contains(s)) {
                    missing.add(s);
                }
            }
        }
        Map<String, BigDecimal> balances = store.balances();
        return new ProcessingReport(rows, counts, retriesScheduled, missing, balances, store.ledger().size());
    }

    // ------------------------------------------------------------------------------------------

    private void drain() {
        while (true) {
            TransactionRecord pending = pendingBySequence.remove(nextExpectedSequence);
            if (pending != null) {
                processRecord(pending);
                nextExpectedSequence++;
                continue;
            }
            if (skippedSequences.remove(nextExpectedSequence)) {
                nextExpectedSequence++;
                continue;
            }
            break;
        }
    }

    private void releaseSlotOfRejectedRecord(Long sequenceHint) {
        if (sequenceHint != null && sequenceHint >= nextExpectedSequence
                && !pendingBySequence.containsKey(sequenceHint)) {
            skippedSequences.add(sequenceHint);
        }
    }

    private void processRecord(TransactionRecord record) {
        TransactionRequest req = record.request();
        String masked = ProcessingEventLog.maskAccount(req.accountId());

        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            long delay = retryPolicy.delayBeforeAttempt(attempt);
            if (delay > 0) {
                sleeper.sleep(delay);
            }
            record.result().setAttempts(attempt);
            record.transition(PROCESSING, null);
            log.event("PROCESSING", "txnId", req.transactionId(), "requestId", req.requestId(),
                    "attempt", attempt, "account", masked, "type", req.type());
            try {
                injector.beforeCommit(req, attempt);
                CommitResult commit = store.commit(req);
                injector.afterCommit(req, attempt);

                String note = commit == CommitResult.ALREADY_APPLIED
                        ? "effect was already applied by an earlier attempt; not applied again" : null;
                record.transition(PROCESSED, note);
                log.event("PROCESSED", "txnId", req.transactionId(), "attempt", attempt,
                        "commit", commit);
                return;
            } catch (TransientProcessingException e) {
                if (attempt < retryPolicy.maxAttempts()) {
                    retriesScheduled++;
                    record.transition(RETRY_PENDING, "transient failure on attempt " + attempt);
                    log.event("RETRY_SCHEDULED", "txnId", req.transactionId(), "failedAttempt", attempt,
                            "reason", e.getMessage());
                } else {
                    record.transition(FAILED, "RETRIES_EXHAUSTED after " + attempt + " attempts");
                    log.event("FAILED", "txnId", req.transactionId(), "reason", "RETRIES_EXHAUSTED",
                            "attempts", attempt);
                    return;
                }
            } catch (BusinessRuleException e) {
                record.transition(FAILED, e.code());
                log.event("FAILED", "txnId", req.transactionId(), "reason", e.code(), "attempts", attempt);
                return;
            } catch (RuntimeException e) {
                // Unknown error: fail safely, log only the type (messages may contain sensitive data).
                record.transition(FAILED, "UNEXPECTED_ERROR");
                log.event("FAILED", "txnId", req.transactionId(), "reason", "UNEXPECTED_ERROR",
                        "errorType", e.getClass().getSimpleName());
                return;
            }
        }
    }

    private void markDuplicate(RawRecord raw, TransactionRequest req, TransactionRecord original) {
        InputResult r = new InputResult(raw.inputOrder(), req.transactionId(), req.requestId());
        r.transition(DUPLICATE, "duplicate of request " + original.request().requestId()
                + " (original status at arrival: " + original.status() + ")");
        results.add(r);
        original.incrementDuplicates();
        store.linkRequest(req.requestId(), req.transactionId());
        log.event("DUPLICATE", "txnId", req.transactionId(), "requestId", req.requestId(),
                "originalStatus", original.status());
    }

    private void reject(RawRecord raw, List<String> reasons) {
        InputResult r = new InputResult(raw.inputOrder(),
                ProcessingEventLog.safe(raw.get(RawRecord.TRANSACTION_ID)),
                ProcessingEventLog.safe(raw.get(RawRecord.REQUEST_ID)));
        r.transition(REJECTED, String.join("; ", reasons));
        results.add(r);
        log.event("REJECTED", "order", raw.inputOrder(), "txnId", r.transactionId(), "reasons", r.reason());
    }
}
