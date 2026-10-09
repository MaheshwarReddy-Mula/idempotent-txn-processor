package com.example.txnprocessor.store;

import com.example.txnprocessor.model.TransactionRecord;
import com.example.txnprocessor.model.TransactionRequest;
import com.example.txnprocessor.model.TransactionType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Thread-safe in-memory store. A single monitor makes {@link #commit} atomic: all checks happen
 * before any mutation, so a failure can never leave a half-applied transaction.
 * In a database this maps to one DB transaction with a UNIQUE constraint on ledger.transaction_id.
 */
public class InMemoryTransactionStore implements TransactionStore {

    private final Map<String, TransactionRecord> byTransactionId = new HashMap<>();
    private final Map<String, String> transactionIdByRequestId = new HashMap<>();
    private final Map<String, LedgerEntry> ledgerByTransactionId = new LinkedHashMap<>();
    private final Set<String> reversedTransactionIds = new HashSet<>();
    private final Map<String, BigDecimal> balances = new HashMap<>();

    @Override
    public synchronized Optional<TransactionRecord> findByTransactionId(String transactionId) {
        return Optional.ofNullable(byTransactionId.get(transactionId));
    }

    @Override
    public synchronized Optional<String> findTransactionIdByRequestId(String requestId) {
        return Optional.ofNullable(transactionIdByRequestId.get(requestId));
    }

    @Override
    public synchronized void register(TransactionRecord record) {
        TransactionRequest r = record.request();
        if (byTransactionId.putIfAbsent(r.transactionId(), record) != null) {
            throw new IllegalStateException("transaction already registered");
        }
        transactionIdByRequestId.put(r.requestId(), r.transactionId());
    }

    @Override
    public synchronized void linkRequest(String requestId, String transactionId) {
        transactionIdByRequestId.putIfAbsent(requestId, transactionId);
    }

    @Override
    public synchronized CommitResult commit(TransactionRequest request) {
        if (ledgerByTransactionId.containsKey(request.transactionId())) {
            return CommitResult.ALREADY_APPLIED;
        }

        // ---- 1. decide the effect (no mutation yet) ----
        BigDecimal delta;
        switch (request.type()) {
            case CREDIT -> delta = request.amount();
            case DEBIT -> delta = request.amount().negate();
            case REVERSAL -> {
                LedgerEntry original = ledgerByTransactionId.get(request.referenceTransactionId());
                if (original == null) {
                    throw new BusinessRuleException("REFERENCE_NOT_PROCESSED");
                }
                if (original.type() == TransactionType.REVERSAL) {
                    throw new BusinessRuleException("REFERENCE_NOT_REVERSIBLE");
                }
                if (!original.accountId().equals(request.accountId())
                        || !original.currency().equals(request.currency())) {
                    throw new BusinessRuleException("REFERENCE_MISMATCH");
                }
                if (reversedTransactionIds.contains(original.transactionId())) {
                    throw new BusinessRuleException("ALREADY_REVERSED");
                }
                delta = original.signedAmount().negate();
            }
            default -> throw new IllegalStateException("unsupported type");
        }

        String key = request.accountId() + "|" + request.currency();
        BigDecimal current = balances.getOrDefault(key, BigDecimal.ZERO);
        BigDecimal next = current.add(delta);
        if (next.signum() < 0) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS");
        }

        // ---- 2. apply everything together ----
        balances.put(key, next);
        ledgerByTransactionId.put(request.transactionId(), new LedgerEntry(
                request.transactionId(), request.accountId(), request.currency(),
                request.type(), delta, next));
        if (request.type() == TransactionType.REVERSAL) {
            reversedTransactionIds.add(request.referenceTransactionId());
        }
        return CommitResult.APPLIED;
    }

    @Override
    public synchronized BigDecimal balanceOf(String accountId, String currency) {
        return balances.getOrDefault(accountId + "|" + currency, BigDecimal.ZERO);
    }

    @Override
    public synchronized Map<String, BigDecimal> balances() {
        return new HashMap<>(balances);
    }

    @Override
    public synchronized List<LedgerEntry> ledger() {
        return new ArrayList<>(ledgerByTransactionId.values());
    }
}
