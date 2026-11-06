package dev.ledgerlane.account;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final RowMapper<Account> MAPPER = (rs, i) -> new Account(
            rs.getString("id"),
            AccountType.fromValue(rs.getString("type")),
            rs.getString("currency"),
            rs.getBoolean("allow_negative"),
            rs.getLong("balance"),
            rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns false if the id is already taken. */
    public boolean insert(String id, AccountType type, String currency, boolean allowNegative) {
        return jdbc.sql("""
                INSERT INTO accounts (id, type, currency, normal_side, allow_negative)
                VALUES (:id, :type, :currency, :side, :allowNegative)
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", id)
                .param("type", type.value())
                .param("currency", currency)
                .param("side", type.debitNormal() ? "debit" : "credit")
                .param("allowNegative", allowNegative)
                .update() == 1;
    }

    public Optional<Account> find(String id) {
        return jdbc.sql("SELECT * FROM accounts WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    /** Locks the rows in ascending id order, which is what keeps concurrent transfers deadlock free. */
    public List<Account> lockAll(Collection<String> ids) {
        return jdbc.sql("SELECT * FROM accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", ids)
                .query(MAPPER)
                .list();
    }

    public void applyDelta(String id, long delta) {
        jdbc.sql("UPDATE accounts SET balance = balance + :delta, version = version + 1 WHERE id = :id")
                .param("delta", delta)
                .param("id", id)
                .update();
    }

    /** Postings of one account, newest first. The cursor is the id of the last posting already seen. */
    public PostingPage postings(String accountId, Long cursor, int limit) {
        List<PostingView> rows = jdbc.sql("""
                SELECT p.id, p.entry_id, e.description, p.amount, p.currency, e.created_at
                FROM postings p JOIN journal_entries e ON e.id = p.entry_id
                WHERE p.account_id = :account AND (CAST(:cursor AS bigint) IS NULL OR p.id < CAST(:cursor AS bigint))
                ORDER BY p.id DESC
                LIMIT :limit
                """)
                .param("account", accountId)
                .param("cursor", cursor)
                .param("limit", limit + 1)
                .query((rs, i) -> new PostingView(rs.getLong("id"), rs.getObject("entry_id", java.util.UUID.class),
                        rs.getString("description"), rs.getLong("amount"), rs.getString("currency"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        boolean more = rows.size() > limit;
        List<PostingView> items = more ? rows.subList(0, limit) : rows;
        return new PostingPage(items, more ? String.valueOf(items.get(limit - 1).id()) : null);
    }
}
