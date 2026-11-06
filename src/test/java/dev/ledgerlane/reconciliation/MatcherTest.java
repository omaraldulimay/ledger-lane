package dev.ledgerlane.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatcherTest {

    static BankLine bank(String ref, long cents) {
        return new BankLine(LocalDate.of(2026, 11, 2), cents, ref, "Counterparty");
    }

    static LedgerLine ledger(String ref, long cents) {
        return new LedgerLine(UUID.randomUUID(), ref, cents, "entry " + ref, Instant.now());
    }

    @Test
    void matchesByReferenceAndAmount() {
        var result = Matcher.match(List.of(bank("o1", 1000), bank("o2", -500)), List.of(ledger("o2", -500), ledger("o1", 1000)));
        assertThat(result.matched()).isEqualTo(2);
        assertThat(result.issues()).isZero();
    }

    @Test
    void findsAllThreeKindsOfDiscrepancy() {
        var result = Matcher.match(
                List.of(bank("o1", 1000), bank("o2", 2001), bank(null, -35)),
                List.of(ledger("o1", 1000), ledger("o2", 2000), ledger("o3", 700)));
        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.missingInLedger()).extracting(BankLine::amountCents).containsExactly(-35L);
        assertThat(result.missingAtBank()).extracting(LedgerLine::reference).containsExactly("o3");
        assertThat(result.amountMismatch()).singleElement().satisfies(m -> {
            assertThat(m.reference()).isEqualTo("o2");
            assertThat(m.bankAmountCents()).isEqualTo(2001);
            assertThat(m.ledgerAmountCents()).isEqualTo(2000);
        });
    }

    @Test
    void duplicateReferencesPairExactAmountsFirst() {
        var result = Matcher.match(List.of(bank("o1", 300), bank("o1", 100)), List.of(ledger("o1", 100), ledger("o1", 300)));
        assertThat(result.matched()).isEqualTo(2);
        assertThat(result.issues()).isZero();
    }

    @Test
    void bankLineWithUnknownReferenceIsMissingInLedger() {
        var result = Matcher.match(List.of(bank("x", 5)), List.of());
        assertThat(result.missingInLedger()).hasSize(1);
    }

    @Test
    void parsesCsvWithQuotedFields() {
        var lines = BankStatementParser.parse("""
                booking_date,amount_cents,reference,counterparty
                2026-11-02,1000,o1,"Müller, Anna"
                2026-11-03,-35,,Bank fee
                """);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).counterparty()).isEqualTo("Müller, Anna");
        assertThat(lines.get(1).reference()).isEmpty();
    }
}
