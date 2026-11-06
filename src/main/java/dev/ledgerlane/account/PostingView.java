package dev.ledgerlane.account;

import java.time.Instant;
import java.util.UUID;

public record PostingView(long id, UUID entryId, String description, long amount, String currency,
                          Instant createdAt) {
}
