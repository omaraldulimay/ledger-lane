package dev.ledgerlane.reconciliation;

import dev.ledgerlane.api.LedgerException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Parses booking_date,amount_cents,reference,counterparty. Fields may be quoted with double quotes. */
public final class BankStatementParser {

    private static final String HEADER = "booking_date,amount_cents,reference,counterparty";

    private BankStatementParser() {
    }

    public static List<BankLine> parse(String csv) {
        List<String> lines = csv.replace("﻿", "").lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty() || !lines.get(0).trim().equalsIgnoreCase(HEADER)) {
            throw LedgerException.invalid("CSV header must be " + HEADER);
        }
        List<BankLine> result = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> f = split(lines.get(i));
            if (f.size() != 4) {
                throw LedgerException.invalid("Line " + (i + 1) + ": expected 4 columns, got " + f.size());
            }
            try {
                result.add(new BankLine(LocalDate.parse(f.get(0).trim()), Long.parseLong(f.get(1).trim()),
                        f.get(2).trim(), f.get(3).trim()));
            } catch (DateTimeParseException | NumberFormatException e) {
                throw LedgerException.invalid("Line " + (i + 1) + ": invalid date or amount");
            }
        }
        return result;
    }

    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
