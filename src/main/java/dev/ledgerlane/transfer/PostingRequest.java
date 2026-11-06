package dev.ledgerlane.transfer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Amount in minor units: positive is a debit, negative is a credit. */
public record PostingRequest(@NotBlank String accountId, @NotNull Long amount, @NotBlank String currency) {
}
