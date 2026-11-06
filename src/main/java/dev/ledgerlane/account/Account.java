package dev.ledgerlane.account;

import java.time.Instant;

/** Balance is the signed sum of postings: debits positive, credits negative. */
public record Account(String id, AccountType type, String currency, boolean allowNegative,
                      long balance, long version, Instant createdAt) {

    /** Balance as seen from the account's normal side, the one that must not go negative. */
    public long normalBalance() {
        return normalBalance(balance);
    }

    public long normalBalance(long signedBalance) {
        return type.debitNormal() ? signedBalance : -signedBalance;
    }
}
