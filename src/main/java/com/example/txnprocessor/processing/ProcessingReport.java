package com.example.txnprocessor.processing;

import com.example.txnprocessor.model.TransactionStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Immutable snapshot of a processing run: summary counts, per-record outcomes, balances. */
public final class ProcessingReport {

    public record Row(int order, String transactionId, String requestId, TransactionStatus status,
                      int attempts, String detail, List<TransactionStatus> lifecycle) {
    }

    private final List<Row> rows;
    private final Map<TransactionStatus, Long> counts;
    private final long retriesScheduled;
    private final List<Long> missingSequences;
    private final Map<String, BigDecimal> balances;
    private final int ledgerEntries;

    public ProcessingReport(List<Row> rows, Map<TransactionStatus, Long> counts, long retriesScheduled,
                            List<Long> missingSequences, Map<String, BigDecimal> balances, int ledgerEntries) {
        this.rows = List.copyOf(rows);
        this.counts = Map.copyOf(counts);
        this.retriesScheduled = retriesScheduled;
        this.missingSequences = List.copyOf(missingSequences);
        this.balances = Map.copyOf(balances);
        this.ledgerEntries = ledgerEntries;
    }

    public List<Row> rows() { return rows; }
    public long countOf(TransactionStatus status) { return counts.getOrDefault(status, 0L); }
    public long retriesScheduled() { return retriesScheduled; }
    public List<Long> missingSequences() { return missingSequences; }
    public Map<String, BigDecimal> balances() { return balances; }
    public int ledgerEntries() { return ledgerEntries; }

    /** Row for the first input record carrying this transactionId (the original, not later duplicates). */
    public Row firstRowFor(String transactionId) {
        return rows.stream().filter(r -> r.transactionId().equals(transactionId)).findFirst().orElse(null);
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("==================== TRANSACTION PROCESSING REPORT ====================\n");
        sb.append(String.format("Input records received : %d%n", rows.size()));
        sb.append("\nSummary by status\n");
        for (TransactionStatus s : TransactionStatus.values()) {
            long c = countOf(s);
            if (c > 0) {
                sb.append(String.format("  %-17s : %d%n", s, c));
            }
        }
        sb.append(String.format("%nRetries scheduled     : %d%n", retriesScheduled));
        sb.append(String.format("Duplicates detected   : %d%n", countOf(TransactionStatus.DUPLICATE)));
        sb.append(String.format("Ledger entries        : %d%n", ledgerEntries));

        sb.append("\nPer-record outcome\n");
        sb.append(String.format("  %-3s %-13s %-14s %-17s %-8s %s%n", "#", "transactionId", "requestId", "status", "attempts", "detail"));
        for (Row r : rows) {
            sb.append(String.format("  %-3d %-13s %-14s %-17s %-8d %s%n",
                    r.order(), r.transactionId(), r.requestId(), r.status(), r.attempts(),
                    r.detail() == null ? "" : r.detail()));
        }

        List<Row> multi = rows.stream().filter(r -> r.lifecycle().size() > 2).toList();
        if (!multi.isEmpty()) {
            sb.append("\nLifecycle of transactions\n");
            for (Row r : multi) {
                sb.append("  ").append(r.transactionId()).append(": ")
                        .append(r.lifecycle().stream().map(Enum::name).collect(Collectors.joining(" -> ")))
                        .append('\n');
            }
        }

        sb.append("\nOrdering\n");
        if (countOf(TransactionStatus.PENDING_SEQUENCE) == 0) {
            sb.append("  No records left waiting for a missing sequence number.\n");
        } else {
            sb.append("  ").append(countOf(TransactionStatus.PENDING_SEQUENCE))
                    .append(" record(s) still waiting. Missing sequence numbers: ")
                    .append(missingSequences).append('\n');
        }

        sb.append("\nBalances\n");
        if (balances.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            balances.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
                String[] parts = e.getKey().split("\\|");
                sb.append(String.format("  %s %s = %s%n", ProcessingEventLog.maskAccount(parts[0]), parts[1],
                        e.getValue().setScale(2, RoundingMode.UNNECESSARY).toPlainString()));
            });
        }
        sb.append("=======================================================================\n");
        return sb.toString();
    }
}
