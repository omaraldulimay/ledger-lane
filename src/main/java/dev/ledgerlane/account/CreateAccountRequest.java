package dev.ledgerlane.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(
        @NotBlank String id,
        @NotNull AccountType type,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        Boolean allowNegative) {
}
