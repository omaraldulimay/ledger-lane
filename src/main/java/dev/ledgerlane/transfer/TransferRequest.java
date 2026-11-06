package dev.ledgerlane.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record TransferRequest(@NotBlank String description, @NotNull List<@Valid @NotNull PostingRequest> postings) {
}
