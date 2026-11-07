package dev.ledgerlane.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Rule 6: per currency, all postings sum to zero and every stored balance equals the sum of its postings. */
final class InvariantChecks {

    private InvariantChecks() {
    }

    static void assertHold(JdbcClient jdbc) {
        List<String> offCurrencies = jdbc.sql("SELECT currency FROM postings GROUP BY currency HAVING SUM(amount) <> 0")
                .query(String.class).list();
        assertThat(offCurrencies).as("currencies whose postings do not sum to zero").isEmpty();

        List<String> drifted = jdbc.sql("""
                SELECT a.id FROM accounts a
                WHERE a.balance <> (SELECT COALESCE(SUM(p.amount), 0) FROM postings p WHERE p.account_id = a.id)
                """).query(String.class).list();
        assertThat(drifted).as("accounts whose balance differs from their postings").isEmpty();

        List<UUID> unbalanced = jdbc.sql("""
                SELECT entry_id FROM postings GROUP BY entry_id HAVING COUNT(*) < 2
                UNION
                SELECT entry_id FROM postings GROUP BY entry_id, currency HAVING SUM(amount) <> 0
                """)
                .query(UUID.class).list();
        assertThat(unbalanced).as("unbalanced entries").isEmpty();
    }
}
