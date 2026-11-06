package dev.ledgerlane.transfer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** amount is optional: without it the brand's whole payable balance is paid out. reference is optional too. */
public record PayoutRequest(@NotBlank String brandId, Long amount, @Size(max = 100) String reference) {

    public PayoutRequest(String brandId, Long amount) {
        this(brandId, amount, null);
    }
}
