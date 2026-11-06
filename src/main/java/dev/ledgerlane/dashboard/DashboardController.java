package dev.ledgerlane.dashboard;

import dev.ledgerlane.reconciliation.ReconciliationResult;
import dev.ledgerlane.reconciliation.ReconciliationService;
import java.time.Instant;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read only view for the Leitstand. Every number is computed with SQL on request. */
@RestController
@RequestMapping("/v1/dashboard")
class DashboardController {

    record AccountRow(String id, String type, long normalBalance, long postingsSum) {
    }

    record EntryRow(String id, Instant createdAt, String description, String reference, long amount) {
    }

    /**
     * entries: number of journal entries. movedTodayCents: sum of positive postings of today's entries (UTC).
     * postingsSumCents: sum of all postings, must be 0. driftedAccounts: accounts whose stored balance differs
     * from the sum of their postings, must be 0. balanced is true if both hold.
     */
    record Dashboard(long entries, long movedTodayCents, long postingsSumCents, long driftedAccounts, boolean balanced,
                     long outboxLag, ReconciliationResult lastReconciliation, List<AccountRow> accounts,
                     List<EntryRow> latestEntries) {
    }

    private final JdbcClient jdbc;
    private final ReconciliationService reconciliation;

    DashboardController(JdbcClient jdbc, ReconciliationService reconciliation) {
        this.jdbc = jdbc;
        this.reconciliation = reconciliation;
    }

    @GetMapping
    ResponseEntity<Dashboard> get() {
        long entries = one("SELECT COUNT(*) FROM journal_entries");
        long moved = one("""
                SELECT COALESCE(SUM(p.amount), 0) FROM postings p JOIN journal_entries e ON e.id = p.entry_id
                WHERE p.amount > 0 AND e.created_at >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
                """);
        long sum = one("SELECT COALESCE(SUM(amount), 0) FROM postings");
        long drifted = one("""
                SELECT COUNT(*) FROM accounts a
                WHERE a.balance <> (SELECT COALESCE(SUM(p.amount), 0) FROM postings p WHERE p.account_id = a.id)
                """);
        long lag = one("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL");
        List<AccountRow> accounts = jdbc.sql("""
                SELECT a.id, a.type,
                       CASE a.normal_side WHEN 'debit' THEN a.balance ELSE -a.balance END AS normal_balance,
                       (SELECT COALESCE(SUM(p.amount), 0) FROM postings p WHERE p.account_id = a.id) AS postings_sum
                FROM accounts a ORDER BY a.id
                """)
                .query((rs, i) -> new AccountRow(rs.getString("id"), rs.getString("type"), rs.getLong("normal_balance"),
                        rs.getLong("postings_sum")))
                .list();
        List<EntryRow> latest = jdbc.sql("""
                SELECT e.id, e.created_at, e.description, e.reference,
                       (SELECT COALESCE(SUM(p.amount), 0) FROM postings p WHERE p.entry_id = e.id AND p.amount > 0) AS amount
                FROM journal_entries e ORDER BY e.created_at DESC, e.id DESC LIMIT 15
                """)
                .query((rs, i) -> new EntryRow(rs.getString("id"), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("description"), rs.getString("reference"), rs.getLong("amount")))
                .list();
        Dashboard d = new Dashboard(entries, moved, sum, drifted, sum == 0 && drifted == 0, lag,
                reconciliation.latest().orElse(null), accounts, latest);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(d);
    }

    private long one(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
