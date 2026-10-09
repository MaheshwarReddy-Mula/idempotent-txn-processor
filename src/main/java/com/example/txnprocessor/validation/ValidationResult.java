package com.example.txnprocessor.validation;

import com.example.txnprocessor.model.TransactionRequest;

import java.util.List;

/**
 * @param request      the validated request (null when invalid)
 * @param errors       validation messages (never echo raw input values -> no sensitive data / log injection)
 * @param sequenceHint the sequence number if it was parseable, even when other fields were invalid.
 *                     Lets a rejected record release its sequence slot so it cannot block later records.
 */
public record ValidationResult(TransactionRequest request, List<String> errors, Long sequenceHint) {
    public boolean valid() {
        return errors.isEmpty();
    }
}
