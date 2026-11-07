package dev.ledgerlane.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlane.PostgresTestBase;
import dev.ledgerlane.account.AccountRepository;
import dev.ledgerlane.account.AccountType;
import dev.ledgerlane.api.LedgerException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A US customer pays 110,00 USD, the brand is owed 85,00 EUR and the platform keeps 15,00 EUR. */
class CurrencyTest extends PostgresTestBase {

    @Autowired TransferService service;
    @Autowired AccountRepository accounts;
    @Autowired JdbcClient jdbc;

    String run, bankUsd, fxUsd, fxEur, payable, revenue;

    @BeforeEach
    void setUp() {
        run = UUID.randomUUID().toString().substring(0, 8);
        bankUsd = "cash:bank-usd:" + run;
        fxUsd = "fx:usd:" + run;
        fxEur = "fx:eur:" + run;
        payable = "brand:" + run + ":payable";
        revenue = "revenue:" + run;
        accounts.insert(bankUsd, AccountType.ASSET, "USD", false);
        accounts.insert(fxUsd, AccountType.EQUITY, "USD", true);
        accounts.insert(fxEur, AccountType.EQUITY, "EUR", true);
        accounts.insert(payable, AccountType.LIABILITY, "EUR", false);
        accounts.insert(revenue, AccountType.REVENUE, "EUR", false);
    }

    @Test
    void exchangeThroughFxAccountsBalancesPerCurrency() {
        service.transfer("k-" + UUID.randomUUID(), new TransferRequest("Order in USD", List.of(
                new PostingRequest(bankUsd, 11000L, "USD"), new PostingRequest(fxUsd, -11000L, "USD"),
                new PostingRequest(fxEur, 10000L, "EUR"), new PostingRequest(payable, -8500L, "EUR"),
                new PostingRequest(revenue, -1500L, "EUR"))));

        assertThat(accounts.find(bankUsd).orElseThrow().normalBalance()).isEqualTo(11000);
        assertThat(accounts.find(payable).orElseThrow().normalBalance()).isEqualTo(8500);
        InvariantChecks.assertHold(jdbc);
    }

    @Test
    void postingInTheWrongCurrencyForTheAccountIsRejected() {
        var postings = List.of(new PostingRequest(bankUsd, 100L, "EUR"), new PostingRequest(payable, -100L, "EUR"));
        assertThatThrownBy(() -> service.transfer("k-" + UUID.randomUUID(), new TransferRequest("wrong", postings)))
                .isInstanceOf(LedgerException.class).hasMessageContaining("holds USD, not EUR");
    }

    @Test
    void databaseRejectsAnEntryThatOnlyBalancesAcrossCurrencies() {
        // bypasses the service on purpose: the deferred trigger is the last line of defence
        assertThatThrownBy(() -> jdbc.sql("""
                WITH e AS (INSERT INTO journal_entries (id, description) VALUES (:id, 'raw') RETURNING id)
                INSERT INTO postings (entry_id, account_id, amount, currency)
                SELECT e.id, v.a, v.amt, v.c FROM e, (VALUES (:usd, 100, 'USD'), (:eur, -100, 'EUR')) v(a, amt, c)
                """).param("id", UUID.randomUUID()).param("usd", bankUsd).param("eur", payable).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("not balanced");
    }
}
