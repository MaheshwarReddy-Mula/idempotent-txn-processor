package com.example.txnprocessor.validation;

import com.example.txnprocessor.model.RawRecord;
import com.example.txnprocessor.model.TransactionRequest;
import com.example.txnprocessor.model.TransactionType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure validation of a RawRecord. Collects every problem instead of failing on the first. */
public class TransactionValidator {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final int MAX_INTEGER_DIGITS = 15;
    private static final int MAX_DECIMALS = 2;

    public ValidationResult validate(RawRecord raw) {
        if (raw.sourceError() != null) {
            return new ValidationResult(null, List.of(raw.sourceError()), null);
        }

        List<String> errors = new ArrayList<>();
        String transactionId = id(raw, RawRecord.TRANSACTION_ID, errors);
        String requestId = id(raw, RawRecord.REQUEST_ID, errors);
        String accountId = id(raw, RawRecord.ACCOUNT_ID, errors);
        Long sequence = sequence(raw, errors);
        TransactionType type = type(raw, errors);
        BigDecimal amount = amount(raw, errors);
        String currency = currency(raw, errors);

        String reference = null;
        if (type == TransactionType.REVERSAL) {
            String ref = trim(raw.get(RawRecord.REFERENCE_TRANSACTION_ID));
            if (ref == null) {
                errors.add("referenceTransactionId is required for REVERSAL");
            } else if (!ID.matcher(ref).matches()) {
                errors.add("referenceTransactionId has invalid format");
            } else {
                reference = ref;
            }
        }

        if (!errors.isEmpty()) {
            return new ValidationResult(null, List.copyOf(errors), sequence);
        }
        TransactionRequest request = new TransactionRequest(
                transactionId, requestId, sequence, accountId, type, amount, currency, reference);
        return new ValidationResult(request, List.of(), sequence);
    }

    private static String id(RawRecord raw, String field, List<String> errors) {
        String v = trim(raw.get(field));
        if (v == null) {
            errors.add(field + " is missing");
            return null;
        }
        if (!ID.matcher(v).matches()) {
            errors.add(field + " has invalid format (letters, digits, '.', '_', '-' up to 64 chars)");
            return null;
        }
        return v;
    }

    private static Long sequence(RawRecord raw, List<String> errors) {
        String v = trim(raw.get(RawRecord.SEQUENCE_NUMBER));
        if (v == null) {
            errors.add("sequenceNumber is missing");
            return null;
        }
        try {
            long s = Long.parseLong(v);
            if (s <= 0) {
                errors.add("sequenceNumber must be a positive integer");
                return null;
            }
            return s;
        } catch (NumberFormatException e) {
            errors.add("sequenceNumber must be a positive integer");
            return null;
        }
    }

    private static TransactionType type(RawRecord raw, List<String> errors) {
        String v = trim(raw.get(RawRecord.TRANSACTION_TYPE));
        if (v == null) {
            errors.add("transactionType is missing");
            return null;
        }
        try {
            return TransactionType.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("transactionType must be one of CREDIT, DEBIT, REVERSAL");
            return null;
        }
    }

    private static BigDecimal amount(RawRecord raw, List<String> errors) {
        String v = trim(raw.get(RawRecord.AMOUNT));
        if (v == null) {
            errors.add("amount is missing");
            return null;
        }
        BigDecimal a;
        try {
            a = new BigDecimal(v);
        } catch (NumberFormatException e) {
            errors.add("amount is not a valid number");
            return null;
        }
        if (a.signum() <= 0) {
            errors.add("amount must be greater than zero");
            return null;
        }
        BigDecimal stripped = a.stripTrailingZeros();
        if (stripped.scale() > MAX_DECIMALS) {
            errors.add("amount allows at most " + MAX_DECIMALS + " decimal places");
            return null;
        }
        if (stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) {
            errors.add("amount is too large");
            return null;
        }
        return a;
    }

    private static String currency(RawRecord raw, List<String> errors) {
        String v = trim(raw.get(RawRecord.CURRENCY));
        if (v == null) {
            errors.add("currency is missing");
            return null;
        }
        if (!CURRENCY.matcher(v).matches()) {
            errors.add("currency must be a 3-letter uppercase ISO 4217 code");
            return null;
        }
        try {
            Currency.getInstance(v);
        } catch (IllegalArgumentException e) {
            errors.add("currency is not a valid ISO 4217 code");
            return null;
        }
        return v;
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
