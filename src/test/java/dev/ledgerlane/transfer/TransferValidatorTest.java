package dev.ledgerlane.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlane.api.LedgerException;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransferValidatorTest {

    private static PostingRequest p(String account, long amount) {
        return new PostingRequest(account, amount, "EUR");
    }

    @Test
    void acceptsBalancedPostingsAndNetsPerAccount() {
        var deltas = TransferValidator.validate(List.of(p("b", -300), p("a", 500), p("b", -200)));
        assertThat(deltas).containsExactly(java.util.Map.entry("a", 500L), java.util.Map.entry("b", -500L));
    }

    @Test
    void rejectsSinglePosting() {
        assertThatThrownBy(() -> TransferValidator.validate(List.of(p("a", 0))))
                .isInstanceOf(LedgerException.class).hasMessageContaining("two postings");
    }

    @Test
    void rejectsUnbalancedPostings() {
        assertThatThrownBy(() -> TransferValidator.validate(List.of(p("a", 100), p("b", -99))))
                .isInstanceOf(LedgerException.class).hasMessageContaining("sum to zero");
    }

    @Test
    void rejectsZeroAmount() {
        assertThatThrownBy(() -> TransferValidator.validate(List.of(p("a", 0), p("b", 0))))
                .isInstanceOf(LedgerException.class).hasMessageContaining("zero");
    }

    @Test
    void rejectsMixedCurrencies() {
        var postings = List.of(p("a", 100), new PostingRequest("b", -100L, "USD"));
        assertThatThrownBy(() -> TransferValidator.validate(postings))
                .isInstanceOf(LedgerException.class).hasMessageContaining("currency");
    }

    @Test
    void rejectsOverflow() {
        assertThatThrownBy(() -> TransferValidator.validate(List.of(p("a", Long.MAX_VALUE), p("b", 1), p("c", -1))))
                .isInstanceOf(LedgerException.class).hasMessageContaining("overflow");
    }
}
