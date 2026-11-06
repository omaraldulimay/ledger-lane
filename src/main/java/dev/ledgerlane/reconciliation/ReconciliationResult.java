package dev.ledgerlane.reconciliation;

import java.util.List;
import java.util.UUID;

public record ReconciliationResult(int matched, List<BankLine> missingInLedger, List<LedgerLine> missingAtBank,
                                   List<Mismatch> amountMismatch) {

    public record Mismatch(String reference, long bankAmountCents, long ledgerAmountCents, UUID entryId) {
    }

    public int issues() {
        return missingInLedger.size() + missingAtBank.size() + amountMismatch.size();
    }
}
