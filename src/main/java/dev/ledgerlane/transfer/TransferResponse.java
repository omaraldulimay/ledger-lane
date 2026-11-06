package dev.ledgerlane.transfer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferResponse(UUID id, String description, UUID reversesEntryId, Instant createdAt,
                               List<PostingRequest> postings, String reference) {
}
