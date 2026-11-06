package dev.ledgerlane.transfer;

import jakarta.validation.constraints.NotBlank;

/** amount is optional: without it the brand's whole payable balance is paid out. */
public record PayoutRequest(@NotBlank String brandId, Long amount) {
}
