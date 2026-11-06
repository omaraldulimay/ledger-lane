package dev.ledgerlane.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerlane.account.Account;
import dev.ledgerlane.account.AccountRepository;
import dev.ledgerlane.api.LedgerException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    /** replayed is true when the response was stored by an earlier request with the same key. */
    public record Result(TransferResponse transfer, boolean replayed) {
    }

    private record Stored(String hash, String response) {
    }

    private final JdbcClient jdbc;
    private final AccountRepository accounts;
    private final ObjectMapper json;

    TransferService(JdbcClient jdbc, AccountRepository accounts, ObjectMapper json) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.json = json;
    }

    @Transactional
    public Result transfer(String key, TransferRequest request) {
        Map<String, Long> deltas = TransferValidator.validate(request.postings());
        return idempotent(key, "POST /v1/transfers " + toJson(request),
                entryId -> post(entryId, request.description(), null, request.postings(), deltas));
    }

    @Transactional
    public Result reverse(String key, UUID entryId) {
        return idempotent(key, "POST /v1/transfers/" + entryId + "/reversal", reversalId -> {
            String description = jdbc.sql("SELECT description FROM journal_entries WHERE id = :id")
                    .param("id", entryId).query(String.class).optional()
                    .orElseThrow(() -> LedgerException.notFound("Entry " + entryId + " does not exist"));
            if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM journal_entries WHERE reverses_entry_id = :id)")
                    .param("id", entryId).query(Boolean.class).single()) {
                throw alreadyReversed(entryId);
            }
            List<PostingRequest> inverse = jdbc.sql(
                            "SELECT account_id, amount, currency FROM postings WHERE entry_id = :id ORDER BY id")
                    .param("id", entryId)
                    .query((rs, i) -> new PostingRequest(rs.getString("account_id"), -rs.getLong("amount"),
                            rs.getString("currency")))
                    .list();
            return post(reversalId, "Reversal of: " + description, entryId, inverse,
                    TransferValidator.validate(inverse));
        });
    }

    /**
     * The key row is inserted first. A concurrent request with the same key waits on the unique index until
     * we commit, then sees the stored response. A failed request rolls the key back, so a retry runs again.
     */
    private Result idempotent(String key, String fingerprint, Function<UUID, TransferResponse> action) {
        String hash = sha256(fingerprint);
        UUID entryId = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_keys (key, request_hash, entry_id) VALUES (:key, :hash, :entryId)
                ON CONFLICT (key) DO NOTHING
                """)
                .param("key", key).param("hash", hash).param("entryId", entryId)
                .update();
        if (inserted == 0) {
            Stored stored = jdbc.sql("SELECT request_hash, response::text AS response FROM idempotency_keys WHERE key = :key")
                    .param("key", key)
                    .query((rs, i) -> new Stored(rs.getString("request_hash"), rs.getString("response")))
                    .single();
            if (!stored.hash().equals(hash)) {
                throw LedgerException.conflict("Idempotency key reused",
                        "Idempotency-Key " + key + " was already used with a different request");
            }
            return new Result(fromJson(stored.response()), true);
        }
        TransferResponse response = action.apply(entryId);
        jdbc.sql("UPDATE idempotency_keys SET response = CAST(:response AS jsonb) WHERE key = :key")
                .param("response", toJson(response)).param("key", key).update();
        return new Result(response, false);
    }

    private TransferResponse post(UUID entryId, String description, UUID reverses, List<PostingRequest> postings,
                                  Map<String, Long> deltas) {
        // deltas is sorted by account id, and lockAll orders by id as well.
        Map<String, Account> locked = accounts.lockAll(deltas.keySet()).stream()
                .collect(Collectors.toMap(Account::id, a -> a));
        for (var e : deltas.entrySet()) {
            Account account = locked.get(e.getKey());
            if (account == null) {
                throw LedgerException.invalid("Account " + e.getKey() + " does not exist");
            }
            String currency = postings.get(0).currency();
            if (!account.currency().equals(currency)) {
                throw LedgerException.invalid("Account " + account.id() + " holds " + account.currency()
                        + ", not " + currency);
            }
            if (!account.allowNegative() && account.normalBalance(account.balance() + e.getValue()) < 0) {
                throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Insufficient funds", "Account " + account.id() + " would go below zero");
            }
        }

        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        try {
            jdbc.sql("INSERT INTO journal_entries (id, description, reverses_entry_id, created_at) "
                            + "VALUES (:id, :description, :reverses, :createdAt)")
                    .param("id", entryId).param("description", description)
                    .param("reverses", reverses).param("createdAt", Timestamp.from(createdAt))
                    .update();
        } catch (DuplicateKeyException e) {
            throw alreadyReversed(reverses);
        }
        for (PostingRequest p : postings) {
            jdbc.sql("INSERT INTO postings (entry_id, account_id, amount, currency) VALUES (:e, :a, :amount, :c)")
                    .param("e", entryId).param("a", p.accountId()).param("amount", p.amount()).param("c", p.currency())
                    .update();
        }
        deltas.forEach(accounts::applyDelta);
        return new TransferResponse(entryId, description, reverses, createdAt, postings);
    }

    private static LedgerException alreadyReversed(UUID entryId) {
        return LedgerException.conflict("Already reversed", "Entry " + entryId + " was already reversed");
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private TransferResponse fromJson(String value) {
        try {
            return json.readValue(value, TransferResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
