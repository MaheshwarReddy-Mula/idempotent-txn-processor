package com.example.txnprocessor.input;

import com.example.txnprocessor.model.RawRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a simple CSV file (header row required, no quoted fields, '#' comment lines allowed).
 * A line that cannot be parsed becomes a "malformed" RawRecord; it never aborts the batch.
 */
public class CsvTransactionSource implements TransactionSource {

    private static final Set<String> REQUIRED_COLUMNS = Set.of(
            RawRecord.TRANSACTION_ID, RawRecord.REQUEST_ID, RawRecord.SEQUENCE_NUMBER,
            RawRecord.ACCOUNT_ID, RawRecord.TRANSACTION_TYPE, RawRecord.AMOUNT, RawRecord.CURRENCY);

    private final Path file;

    public CsvTransactionSource(Path file) {
        this.file = file;
    }

    @Override
    public List<RawRecord> readAll() {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read input file", e);
        }

        String[] header = null;
        List<RawRecord> out = new ArrayList<>();
        int order = 0;
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (header == null) {
                header = splitLine(line);
                List<String> cols = List.of(header);
                if (!cols.containsAll(REQUIRED_COLUMNS)) {
                    throw new IllegalArgumentException("Input header must contain columns: " + REQUIRED_COLUMNS);
                }
                continue;
            }
            order++;
            String[] values = splitLine(line);
            if (values.length != header.length) {
                out.add(RawRecord.malformed(order, "malformed line: expected " + header.length + " columns"));
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (int i = 0; i < header.length; i++) {
                fields.put(header[i], values[i]);
            }
            out.add(new RawRecord(order, fields, null));
        }
        if (header == null) {
            throw new IllegalArgumentException("Input file has no header row");
        }
        return out;
    }

    private static String[] splitLine(String line) {
        String[] parts = line.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }
}
