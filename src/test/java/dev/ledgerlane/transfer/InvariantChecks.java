package dev.ledgerlane.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Rule 6: all postings sum to zero and every stored balance equals the sum of its postings. */
final class InvariantChecks {

    private InvariantChecks() {
    }

    static void assertHold(JdbcClient jdbc) {
        Long total = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM postings").query(Long.class).single();
        assertThat(total).as("sum of all postings").isZero();

        List<String> drifted = jdbc.sql("""
                SELECT a.id FROM accounts a
                WHERE a.balance <> (SELECT COALESCE(SUM(p.amount), 0) FROM postings p WHERE p.account_id = a.id)
                """).query(String.class).list();
        assertThat(drifted).as("accounts whose balance differs from their postings").isEmpty();

        List<UUID> unbalanced = jdbc.sql("SELECT entry_id FROM postings GROUP BY entry_id HAVING SUM(amount) <> 0 OR COUNT(*) < 2")
                .query(UUID.class).list();
        assertThat(unbalanced).as("unbalanced entries").isEmpty();
    }
}
