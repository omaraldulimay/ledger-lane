package dev.ledgerlane.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** reference is optional (order id, payout id) and is what a bank statement line is matched on. */
public record TransferRequest(@NotBlank String description, @NotNull List<@Valid @NotNull PostingRequest> postings,
                              @Size(max = 100) String reference) {

    public TransferRequest(String description, List<PostingRequest> postings) {
        this(description, postings, null);
    }
}
