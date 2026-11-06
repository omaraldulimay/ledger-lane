package dev.ledgerlane.reconciliation;

import java.time.Instant;
import java.util.UUID;

/** Net effect of one journal entry on the bank accounts (cash:bank and payouts:in-transit), in bank terms. */
public record LedgerLine(UUID entryId, String reference, long amountCents, String description, Instant createdAt) {
}
