package dev.ledgerlane.account;

import java.time.Instant;

public record AccountResponse(String id, AccountType type, String currency, boolean allowNegative,
                              long balance, long normalBalance, long version, Instant createdAt) {

    static AccountResponse from(Account a) {
        return new AccountResponse(a.id(), a.type(), a.currency(), a.allowNegative(),
                a.balance(), a.normalBalance(), a.version(), a.createdAt());
    }
}
