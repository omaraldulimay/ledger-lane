package dev.ledgerlane.reconciliation;

import tools.jackson.databind.ObjectMapper;
import dev.ledgerlane.transfer.TransferService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransferService transfers;

    ReconciliationService(JdbcClient jdbc, ObjectMapper json, TransferService transfers) {
        this.jdbc = jdbc;
        this.json = json;
        this.transfers = transfers;
    }

    /**
     * Matches the statement and, in the same transaction, settles every matched payout that is still in transit.
     * Payouts are settled in ascending entry id order, so concurrent runs lock them in the same order.
     */
    @Transactional
    public ReconciliationResult reconcile(String csv) {
        Matcher.Outcome outcome = Matcher.matchWithIds(BankStatementParser.parse(csv), ledgerLines());
        List<String> settled = new ArrayList<>();
        for (UUID id : outcome.matchedEntryIds().stream().sorted().toList()) {
            transfers.settlePayout(id).ifPresent(r -> settled.add(r.reference()));
        }
        ReconciliationResult r = outcome.result();
        ReconciliationResult result = new ReconciliationResult(r.matched(), r.missingInLedger(), r.missingAtBank(),
                r.amountMismatch(), settled);
        jdbc.sql("INSERT INTO reconciliation_runs (result) VALUES (CAST(:r AS jsonb))")
                .param("r", json.writeValueAsString(result)).update();
        return result;
    }

    public Optional<ReconciliationResult> latest() {
        return jdbc.sql("SELECT result::text FROM reconciliation_runs ORDER BY id DESC LIMIT 1")
                .query(String.class).optional().map(s -> json.readValue(s, ReconciliationResult.class));
    }

    /** Entries with a reference, by their net posting on the bank accounts. Entries without a reference are not matchable. */
    List<LedgerLine> ledgerLines() {
        return jdbc.sql("""
                SELECT e.id, e.reference, e.description, e.created_at, SUM(p.amount) AS amount
                FROM journal_entries e JOIN postings p ON p.entry_id = e.id
                WHERE e.reference IS NOT NULL AND p.account_id IN ('cash:bank', 'payouts:in-transit')
                GROUP BY e.id, e.reference, e.description, e.created_at
                HAVING SUM(p.amount) <> 0
                ORDER BY e.created_at, e.id
                """)
                .query((rs, i) -> new LedgerLine(rs.getObject("id", java.util.UUID.class), rs.getString("reference"),
                        rs.getLong("amount"), rs.getString("description"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
