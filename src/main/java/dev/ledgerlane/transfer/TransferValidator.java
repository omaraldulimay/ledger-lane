package dev.ledgerlane.transfer;

import dev.ledgerlane.api.LedgerException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Stateless rules that need no database. Account existence and overdraft are checked under lock. */
final class TransferValidator {

    private TransferValidator() {
    }

    /** Checks the posting list and returns the net delta per account, sorted by account id. */
    static Map<String, Long> validate(List<PostingRequest> postings) {
        if (postings.size() < 2) {
            throw LedgerException.invalid("A transfer needs at least two postings");
        }
        String currency = postings.get(0).currency();
        long sum = 0;
        Map<String, Long> deltas = new TreeMap<>();
        try {
            for (PostingRequest p : postings) {
                if (p.amount() == 0) {
                    throw LedgerException.invalid("Posting amounts must not be zero");
                }
                if (!p.currency().equals(currency)) {
                    throw LedgerException.invalid("All postings must use the same currency");
                }
                sum = Math.addExact(sum, p.amount());
                deltas.merge(p.accountId(), p.amount(), Math::addExact);
            }
        } catch (ArithmeticException e) {
            throw LedgerException.invalid("Amount overflow");
        }
        if (sum != 0) {
            throw LedgerException.invalid("Postings must sum to zero, but sum to " + sum);
        }
        return deltas;
    }
}
