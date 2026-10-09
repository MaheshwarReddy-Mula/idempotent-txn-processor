package com.example.txnprocessor.store;

import com.example.txnprocessor.model.TransactionRecord;
import com.example.txnprocessor.model.TransactionRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence boundary: idempotency index + transaction state + ledger/balances.
 * The in-memory implementation can be replaced by a JDBC/JPA one without touching the processor.
 */
public interface TransactionStore {

    Optional<TransactionRecord> findByTransactionId(String transactionId);

    Optional<String> findTransactionIdByRequestId(String requestId);

    void register(TransactionRecord record);

    /** Remember that a (possibly new) requestId belongs to a transaction, so replays are detected by key. */
    void linkRequest(String requestId, String transactionId);

    /**
     * Atomically applies the business effect (ledger entry + balance) exactly once per transactionId.
     * Either everything is applied or nothing is. Calling it again for the same transactionId is a no-op.
     *
     * @throws BusinessRuleException for non-retryable business failures; state is unchanged in that case
     */
    CommitResult commit(TransactionRequest request);

    BigDecimal balanceOf(String accountId, String currency);

    /** key = accountId|currency */
    Map<String, BigDecimal> balances();

    List<LedgerEntry> ledger();
}
