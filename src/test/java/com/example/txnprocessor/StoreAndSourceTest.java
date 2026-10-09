package com.example.txnprocessor;

import com.example.txnprocessor.input.CsvTransactionSource;
import com.example.txnprocessor.model.RawRecord;
import com.example.txnprocessor.model.TransactionRequest;
import com.example.txnprocessor.model.TransactionType;
import com.example.txnprocessor.store.CommitResult;
import com.example.txnprocessor.store.InMemoryTransactionStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class StoreAndSourceTest {

    @Test
    void concurrentCommitsOfTheSameTransactionApplyOnlyOnce() throws Exception {
        InMemoryTransactionStore store = new InMemoryTransactionStore();
        TransactionRequest req = new TransactionRequest("T1", "R1", 1, "A1",
                TransactionType.CREDIT, new BigDecimal("100"), "INR", null);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<CommitResult>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Callable<CommitResult> task = () -> store.commit(req);
            futures.add(pool.submit(task));
        }
        int applied = 0;
        for (Future<CommitResult> f : futures) {
            if (f.get() == CommitResult.APPLIED) {
                applied++;
            }
        }
        pool.shutdown();

        assertEquals(1, applied);
        assertEquals(0, new BigDecimal("100").compareTo(store.balanceOf("A1", "INR")));
        assertEquals(1, store.ledger().size());
    }

    @Test
    void csvSourceIsolatesMalformedLinesAndSkipsCommentsAndBlankLines() throws Exception {
        Path file = Files.createTempFile("txn-input", ".csv");
        try {
            Files.writeString(file, String.join("\n",
                    "# comment",
                    "transactionId,requestId,sequenceNumber,accountId,transactionType,amount,currency,referenceTransactionId",
                    "T1,R1,1,A1,CREDIT,100,INR,",
                    "",
                    "broken,line",
                    "T2,R2,2,A1,DEBIT,40,INR,") + "\n", StandardCharsets.UTF_8);

            List<RawRecord> records = new CsvTransactionSource(file).readAll();

            assertEquals(3, records.size());
            assertEquals("T1", records.get(0).get(RawRecord.TRANSACTION_ID));
            assertNotNull(records.get(1).sourceError());
            assertEquals("T2", records.get(2).get(RawRecord.TRANSACTION_ID));
            assertEquals(3, records.get(2).inputOrder());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void csvSourceRejectsFileWithoutRequiredColumns() throws Exception {
        Path file = Files.createTempFile("txn-input", ".csv");
        try {
            Files.writeString(file, "foo,bar\n1,2\n", StandardCharsets.UTF_8);
            boolean failed = false;
            try {
                new CsvTransactionSource(file).readAll();
            } catch (IllegalArgumentException e) {
                failed = true;
            }
            assertTrue(failed);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
