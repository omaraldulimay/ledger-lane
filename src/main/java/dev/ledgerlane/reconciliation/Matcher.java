package dev.ledgerlane.reconciliation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches bank lines against ledger lines by reference and amount. Per reference, lines with equal amounts are
 * paired first; what is left is paired in order as an amount mismatch; the rest is missing on one side.
 * Bank lines without a reference (fees, interest) can never match and are missing in the ledger.
 */
public final class Matcher {

    private Matcher() {
    }

    public static ReconciliationResult match(List<BankLine> bank, List<LedgerLine> ledger) {
        List<BankLine> missingInLedger = new ArrayList<>();
        List<LedgerLine> missingAtBank = new ArrayList<>();
        List<ReconciliationResult.Mismatch> mismatches = new ArrayList<>();
        int matched = 0;

        Map<String, List<BankLine>> bankByRef = new LinkedHashMap<>();
        for (BankLine b : bank) {
            if (b.reference() == null || b.reference().isBlank()) {
                missingInLedger.add(b);
            } else {
                bankByRef.computeIfAbsent(b.reference(), k -> new ArrayList<>()).add(b);
            }
        }
        Map<String, List<LedgerLine>> ledgerByRef = new LinkedHashMap<>();
        for (LedgerLine l : ledger) {
            ledgerByRef.computeIfAbsent(l.reference(), k -> new ArrayList<>()).add(l);
        }

        for (var e : bankByRef.entrySet()) {
            List<BankLine> bs = new ArrayList<>(e.getValue());
            List<LedgerLine> ls = new ArrayList<>(ledgerByRef.getOrDefault(e.getKey(), List.of()));
            for (var it = bs.iterator(); it.hasNext(); ) {
                BankLine b = it.next();
                var exact = ls.stream().filter(l -> l.amountCents() == b.amountCents()).findFirst();
                if (exact.isPresent()) {
                    ls.remove(exact.get());
                    it.remove();
                    matched++;
                }
            }
            while (!bs.isEmpty() && !ls.isEmpty()) {
                BankLine b = bs.remove(0);
                LedgerLine l = ls.remove(0);
                mismatches.add(new ReconciliationResult.Mismatch(e.getKey(), b.amountCents(), l.amountCents(), l.entryId()));
            }
            missingInLedger.addAll(bs);
            ledgerByRef.put(e.getKey(), ls);
        }
        for (var e : ledgerByRef.entrySet()) {
            missingAtBank.addAll(e.getValue());
        }
        return new ReconciliationResult(matched, missingInLedger, missingAtBank, mismatches);
    }
}
