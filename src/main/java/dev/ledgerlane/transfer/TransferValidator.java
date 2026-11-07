package dev.ledgerlane.transfer;

import dev.ledgerlane.api.LedgerException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Stateless rules that need no database. Account existence, currency and overdraft are checked under lock. */
final class TransferValidator {

    private TransferValidator() {
    }

    /**
     * Checks the posting list and returns the net delta per account, sorted by account id.
     * An entry may mix currencies (a currency exchange), but the postings of each currency must sum to zero on
     * their own. Cents of different currencies are never added together.
     */
    static Map<String, Long> validate(List<PostingRequest> postings) {
        if (postings.size() < 2) {
            throw LedgerException.invalid("A transfer needs at least two postings");
        }
        Map<String, Long> sumByCurrency = new TreeMap<>();
        Map<String, String> currencyByAccount = new HashMap<>();
        Map<String, Long> deltas = new TreeMap<>();
        try {
            for (PostingRequest p : postings) {
                if (p.amount() == 0) {
                    throw LedgerException.invalid("Posting amounts must not be zero");
                }
                if (!p.currency().matches("[A-Z]{3}")) {
                    throw LedgerException.invalid("Currency must be an ISO 4217 code like EUR, got " + p.currency());
                }
                String before = currencyByAccount.putIfAbsent(p.accountId(), p.currency());
                if (before != null && !before.equals(p.currency())) {
                    throw LedgerException.invalid("Account " + p.accountId() + " is posted in two currencies");
                }
                sumByCurrency.merge(p.currency(), p.amount(), Math::addExact);
                deltas.merge(p.accountId(), p.amount(), Math::addExact);
            }
        } catch (ArithmeticException e) {
            throw LedgerException.invalid("Amount overflow");
        }
        sumByCurrency.forEach((currency, sum) -> {
            if (sum != 0) {
                throw LedgerException.invalid("Postings in " + currency + " must sum to zero, but sum to " + sum);
            }
        });
        return deltas;
    }
}
