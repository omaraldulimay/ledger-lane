package dev.ledgerlane.reconciliation;

import java.util.List;
import java.util.UUID;

/** settledPayouts: references of payouts this run closed because the bank statement showed them. */
public record ReconciliationResult(int matched, List<BankLine> missingInLedger, List<LedgerLine> missingAtBank,
                                   List<Mismatch> amountMismatch, List<String> settledPayouts) {

    public ReconciliationResult {
        settledPayouts = settledPayouts == null ? List.of() : settledPayouts;   // runs stored before settlement existed
    }

    public record Mismatch(String reference, long bankAmountCents, long ledgerAmountCents, UUID entryId) {
    }

    public int issues() {
        return missingInLedger.size() + missingAtBank.size() + amountMismatch.size();
    }
}
