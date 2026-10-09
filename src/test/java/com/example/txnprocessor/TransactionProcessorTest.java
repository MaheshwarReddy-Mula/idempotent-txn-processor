package com.example.txnprocessor;

import com.example.txnprocessor.model.RawRecord;
import com.example.txnprocessor.model.TransactionStatus;
import com.example.txnprocessor.processing.ConfigurableFailureInjector;
import com.example.txnprocessor.processing.FailureInjector;
import com.example.txnprocessor.processing.ProcessingEventLog;
import com.example.txnprocessor.processing.ProcessingReport;
import com.example.txnprocessor.processing.RetryPolicy;
import com.example.txnprocessor.processing.TransactionProcessor;
import com.example.txnprocessor.store.InMemoryTransactionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import static com.example.txnprocessor.model.TransactionStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class TransactionProcessorTest {

    private InMemoryTransactionStore store;
    private ProcessingEventLog log;

    @BeforeEach
    void setUp() {
        store = new InMemoryTransactionStore();
        log = new ProcessingEventLog(null, Clock.systemUTC());
    }

    private TransactionProcessor processor(FailureInjector injector, int maxAttempts) {
        return new TransactionProcessor(store, injector, new RetryPolicy(maxAttempts, 0), log);
    }

    private static RawRecord rec(int order, String txn, String req, long seq, String acct, String type, String amount) {
        return RawRecord.of(order, txn, req, Long.toString(seq), acct, type, amount, "INR");
    }

    private BigDecimal balance(String acct) {
        return store.balanceOf(acct, "INR");
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    // ---------------------------------------------------------------- the assessment's sample data

    @Test
    void suggestedTestDataProducesExpectedOutcomes() {
        TransactionProcessor p = processor(new ConfigurableFailureInjector().failBeforeCommit("TXN-2005", 1), 3);
        ProcessingReport report = p.processAll(List.of(
                rec(1, "TXN-2002", "REQ-2", 2, "ACC-201", "DEBIT", "40"),
                rec(2, "TXN-2001", "REQ-1", 1, "ACC-201", "CREDIT", "100"),
                rec(3, "TXN-2002", "REQ-2-DUP", 2, "ACC-201", "DEBIT", "40"),
                rec(4, "TXN-2003", "REQ-3", 3, "ACC-201", "CREDIT", "25"),
                rec(5, "TXN-2004", "REQ-4", 4, "ACC-202", "DEBIT", "-10"),
                rec(6, "TXN-2005", "REQ-5", 5, "ACC-203", "CREDIT", "500")));

        assertEquals(4, report.countOf(PROCESSED));
        assertEquals(1, report.countOf(DUPLICATE));
        assertEquals(1, report.countOf(REJECTED));
        assertEquals(6, report.rows().size());
        assertEquals(1, report.retriesScheduled());
        assertMoney("85", balance("ACC-201"));   // 100 - 40 + 25, each applied once
        assertMoney("0", balance("ACC-202"));    // invalid record had no effect
        assertMoney("500", balance("ACC-203"));
        assertEquals(4, report.ledgerEntries());
    }

    // ---------------------------------------------------------------- duplicates

    @Test
    void duplicateRequestDoesNotApplyTwice() {
        TransactionProcessor p = processor(FailureInjector.none(), 3);
        ProcessingReport r = p.processAll(List.of(
                rec(1, "T1", "R1", 1, "A1", "CREDIT", "100"),
                rec(2, "T1", "R1", 1, "A1", "CREDIT", "100"),        // exact replay (same requestId)
                rec(3, "T1", "R1-retry", 1, "A1", "CREDIT", "100"))); // client retry with a new requestId
        assertEquals(1, r.countOf(PROCESSED));
        assertEquals(2, r.countOf(DUPLICATE));
        assertMoney("100", balance("A1"));
        assertEquals(1, r.ledgerEntries());
    }

    @Test
    void duplicateArrivingWhileOriginalIsStillPendingIsNotBufferedTwice() {
        TransactionProcessor p = processor(FailureInjector.none(), 3);
        p.ingest(rec(1, "T2", "R2", 2, "A1", "DEBIT", "40"));      // pending, waiting for seq 1
        p.ingest(rec(2, "T2", "R2-DUP", 2, "A1", "DEBIT", "40"));  // duplicate of a pending record
        p.ingest(rec(3, "T1", "R1", 1, "A1", "CREDIT", "100"));
        ProcessingReport r = p.buildReport();
        assertEquals(2, r.countOf(PROCESSED));
        assertEquals(1, r.countOf(DUPLICATE));
        assertMoney("60", balance("A1"));
    }

    @Test
    void sameTransactionWithDifferentPayloadIsRejectedAsConflict() {
        TransactionProcessor p = processor(FailureInjector.none(), 3);
        p.ingest(rec(1, "T1", "R1", 1, "A1", "CREDIT", "100"));
        p.ingest(rec(2, "T1", "R9", 1, "A1", "CREDIT", "999"));
        ProcessingReport r = p.buildReport();
        assertEquals(REJECTED, r.rows().get(1).status());
        assertMoney("100", balance("A1"));
    }

    @Test
    void requestIdReusedForDifferentTransactionIsRejected() {
        TransactionProcessor p = processor(FailureInjector.none(), 3);
        p.ingest(rec(1, "T1", "R1", 1, "A1", "CREDIT", "100"));
        p.ingest(rec(2, "T2", "R1", 2, "A1", "CREDIT", "50"));
        assertEquals(REJECTED, p.buildReport().rows().get(1).status());
        assertMoney("100", balance("A1"));
    }

    // ---------------------------------------------------------------- ordering

    @Test
    void futureSequenceIsBufferedUntilPredecessorArrives() {
        TransactionProcessor p = processor(FailureInjector.none(), 3);
        p.ingest(rec(1, "T2", "R2", 2, "A1", "DEBIT", "40"));
        assertEquals(PENDING_SEQUENCE, p.buildReport().firstRowFor("T2").status());
        assertMoney("0", balance("A1")); // NOT applied: it would have overdrawn the account

        p.ingest(rec(2, "T1", "R1", 1, "A1", "CREDIT", "100"));
        ProcessingReport r = p.buildReport();
        assertEquals(PROCESSED, r.firstRowFor("T1").status());
        assertEquals(PROCESSED, r.firstRowFor("T2").status());
        assertMoney("60", balance("A1"));
    }

    @Test
    void missingSequenceNumbersAreReportedAndLaterRecordsStayPending() {
        TransactionProcessor p = processor(FailureInjector.none(), 3);
        ProcessingReport r = p.processAll(List.of(
                rec(1, "T1", "R1", 1, "A1", "CREDIT", "100"),
                rec(2, "T4", "R4", 4, "A1", "CREDIT", "10")));
        assertEquals(PENDING_SEQUENCE, r.firstRowFor("T4").status());
        assertEquals(List.of(2L, 3L), r.missingSequences());
        assertMoney("100", balance("A1"));
    }

    @Test
    void lateRecordForAlreadyResolvedSequenceSlotIsRejected() {
        TransactionProcessor p = processor(FailureInjector.none(), 3);
        p.ingest(rec(1, "T1", "R1", 1, "A1", "CREDIT", "100"));
        p.ingest(rec(2, "TX", "RX", 1, "A1", "CREDIT", "5")); // different txn claiming slot 1
        assertEquals(REJECTED, p.buildReport().rows().get(1).status());
        assertMoney("100", balance("A1"));
    }

    @Test
    void fullyReversedInputOrderStillEndsInSameFinalState() {
        TransactionProcessor p = processor(FailureInjector.none(), 3);
        p.processAll(List.of(
                rec(1, "T3", "R3", 3, "A1", "CREDIT", "25"),
                rec(2, "T2", "R2", 2, "A1", "DEBIT", "40"),
                rec(3, "T1", "R1", 1, "A1", "CREDIT", "100")));
        assertMoney("85", balance("A1"));
    }

    // ---------------------------------------------------------------- validation / isolation

    @Test
    void invalidRecordsAreIsolatedAndDoNotStopTheBatchOrBlockSequence() {
        TransactionProcessor p = processor(FailureInjector.none(), 3);
        ProcessingReport r = p.processAll(List.of(
                rec(1, "T1", "R1", 1, "A1", "CREDIT", "100"),
                rec(2, "T2", "R2", 2, "A1", "DEBIT", "-10"),            // negative amount
                RawRecord.of(3, "T3", "R3", "3", "A1", "CREDIT", "10", "ZZZ"), // invalid currency
                RawRecord.of(4, "T4", "R4", "4", "A1", "TRANSFER", "10", "INR"), // invalid type
                RawRecord.of(5, "T5", "R5", "5", "A1", "CREDIT", "abc", "INR"),  // not a number
                RawRecord.of(6, "", "R6", "6", "A1", "CREDIT", "10", "INR"),     // missing id
                RawRecord.malformed(7, "malformed line: expected 8 columns"),
                rec(8, "T7", "R7", 7, "A1", "CREDIT", "50")));                  // valid, must still be processed
        assertEquals(2, r.countOf(PROCESSED));
        assertEquals(6, r.countOf(REJECTED));
        assertEquals(PROCESSED, r.firstRowFor("T7").status());
        assertMoney("150", balance("A1"));
    }

    @Test
    void validationMessagesDoNotEchoRawValues() {
        TransactionProcessor p = processor(FailureInjector.none(), 3);
        p.ingest(RawRecord.of(1, "T1", "R1", "1", "A1", "CREDIT", "secret-9999", "INR"));
        String detail = p.buildReport().rows().get(0).detail();
        assertFalse(detail.contains("secret-9999"));
    }

    // ---------------------------------------------------------------- retries & failures

    @Test
    void transientFailureIsRetriedAndAppliedExactlyOnce() {
        TransactionProcessor p = processor(new ConfigurableFailureInjector().failBeforeCommit("T1", 1), 3);
        ProcessingReport r = p.processAll(List.of(rec(1, "T1", "R1", 1, "A1", "CREDIT", "100")));
        ProcessingReport.Row row = r.firstRowFor("T1");
        assertEquals(PROCESSED, row.status());
        assertEquals(2, row.attempts());
        assertEquals(List.of(RECEIVED, PROCESSING, RETRY_PENDING, PROCESSING, PROCESSED), row.lifecycle());
        assertMoney("100", balance("A1"));
        assertEquals(1, r.ledgerEntries());
    }

    @Test
    void lostAcknowledgementAfterCommitDoesNotDoubleApplyOnRetry() {
        TransactionProcessor p = processor(new ConfigurableFailureInjector().failAfterCommit("T1", 1), 3);
        ProcessingReport r = p.processAll(List.of(rec(1, "T1", "R1", 1, "A1", "CREDIT", "100")));
        assertEquals(PROCESSED, r.firstRowFor("T1").status());
        assertEquals(2, r.firstRowFor("T1").attempts());
        assertMoney("100", balance("A1")); // applied by attempt 1, attempt 2 was an idempotent no-op
        assertEquals(1, r.ledgerEntries());
    }

    @Test
    void retriesExhaustedMovesToFailedWithoutAnyEffectAndDoesNotBlockLaterRecords() {
        TransactionProcessor p = processor(new ConfigurableFailureInjector().failBeforeCommit("T1", 99), 3);
        ProcessingReport r = p.processAll(List.of(
                rec(1, "T1", "R1", 1, "A1", "CREDIT", "100"),
                rec(2, "T2", "R2", 2, "A2", "CREDIT", "50")));
        ProcessingReport.Row failed = r.firstRowFor("T1");
        assertEquals(FAILED, failed.status());
        assertEquals(3, failed.attempts());
        assertTrue(failed.detail().contains("RETRIES_EXHAUSTED"));
        assertMoney("0", balance("A1"));            // no partial effect
        assertEquals(PROCESSED, r.firstRowFor("T2").status());
        assertMoney("50", balance("A2"));
        assertEquals(2, r.retriesScheduled());
    }

    @Test
    void businessFailureIsNotRetried() {
        TransactionProcessor p = processor(FailureInjector.none(), 3);
        ProcessingReport r = p.processAll(List.of(rec(1, "T1", "R1", 1, "A1", "DEBIT", "10")));
        ProcessingReport.Row row = r.firstRowFor("T1");
        assertEquals(FAILED, row.status());
        assertEquals("INSUFFICIENT_FUNDS", row.detail());
        assertEquals(1, row.attempts());
        assertEquals(0, r.retriesScheduled());
    }

    @Test
    void duplicateOfAFailedTransactionIsNotReprocessed() {
        TransactionProcessor p = processor(FailureInjector.none(), 3);
        p.ingest(rec(1, "T1", "R1", 1, "A1", "DEBIT", "10"));      // fails: no funds
        p.ingest(rec(2, "T1", "R1-again", 1, "A1", "DEBIT", "10"));
        ProcessingReport r = p.buildReport();
        assertEquals(FAILED, r.rows().get(0).status());
        assertEquals(DUPLICATE, r.rows().get(1).status());
    }

    // ---------------------------------------------------------------- reversal

    @Test
    void reversalUndoesOriginalExactlyOnce() {
        TransactionProcessor p = processor(FailureInjector.none(), 3);
        ProcessingReport r = p.processAll(List.of(
                rec(1, "T1", "R1", 1, "A1", "CREDIT", "100"),
                rec(2, "T2", "R2", 2, "A1", "DEBIT", "30"),
                RawRecord.of(3, "T3", "R3", "3", "A1", "REVERSAL", "30", "INR", "T2"),
                RawRecord.of(4, "T4", "R4", "4", "A1", "REVERSAL", "30", "INR", "T2"))); // second reversal
        assertEquals(PROCESSED, r.firstRowFor("T3").status());
        assertEquals(FAILED, r.firstRowFor("T4").status());
        assertEquals("ALREADY_REVERSED", r.firstRowFor("T4").detail());
        assertMoney("100", balance("A1"));
    }

    @Test
    void reversalWithoutReferenceIsRejected() {
        TransactionProcessor p = processor(FailureInjector.none(), 3);
        p.ingest(RawRecord.of(1, "T1", "R1", "1", "A1", "REVERSAL", "30", "INR"));
        assertEquals(REJECTED, p.buildReport().rows().get(0).status());
    }

    // ---------------------------------------------------------------- observability

    @Test
    void logsAreStructuredMaskAccountsAndNeverContainAmounts() {
        TransactionProcessor p = processor(new ConfigurableFailureInjector().failBeforeCommit("T1", 1), 3);
        p.processAll(List.of(
                rec(1, "T1", "R1", 1, "ACC-123456", "CREDIT", "98765.43"),
                rec(2, "T1", "R1b", 1, "ACC-123456", "CREDIT", "98765.43")));
        String all = String.join("\n", log.lines());
        assertTrue(all.contains("account=***456"));
        assertFalse(all.contains("ACC-123456"));
        assertFalse(all.contains("98765"));
        assertEquals(1, log.countOf("RETRY_SCHEDULED"));
        assertEquals(1, log.countOf("DUPLICATE"));
        assertEquals(1, log.countOf("PROCESSED"));
    }

    @Test
    void reportTextContainsSummaryCounts() {
        TransactionProcessor p = processor(FailureInjector.none(), 3);
        String text = p.processAll(List.of(rec(1, "T1", "R1", 1, "A1", "CREDIT", "1"))).toText();
        assertTrue(text.contains("PROCESSED"));
        assertTrue(text.contains("Input records received : 1"));
    }
}
