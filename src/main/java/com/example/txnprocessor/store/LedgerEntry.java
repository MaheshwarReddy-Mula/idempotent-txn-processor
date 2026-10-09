package com.example.txnprocessor.store;

import com.example.txnprocessor.model.TransactionType;

import java.math.BigDecimal;

/** One immutable ledger line. At most one exists per transactionId. */
public record LedgerEntry(String transactionId, String accountId, String currency,
                          TransactionType type, BigDecimal signedAmount, BigDecimal balanceAfter) {
}
