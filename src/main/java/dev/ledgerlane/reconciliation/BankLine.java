package dev.ledgerlane.reconciliation;

import java.time.LocalDate;

/** One line of the bank statement. Positive amounts are money in. */
public record BankLine(LocalDate bookingDate, long amountCents, String reference, String counterparty) {
}
