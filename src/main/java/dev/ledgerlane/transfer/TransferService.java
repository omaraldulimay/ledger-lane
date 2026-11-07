package dev.ledgerlane.transfer;

import tools.jackson.databind.ObjectMapper;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    /** replayed is true when the response was stored by an earlier request with the same key. */
    public record Result(TransferResponse transfer, boolean replayed) {
    }

    private record Stored(String hash, String response) {
    }

    static final String BANK = "cash:bank";
    static final String IN_TRANSIT = "payouts:in-transit";

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
                entryId -> post(entryId, request.description(), null, null, request.reference(), request.postings(), deltas));
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
            return post(reversalId, "Reversal of: " + description, entryId, null, null, inverse,
                    TransferValidator.validate(inverse));
        });
    }

    /**
     * Pays out a brand's payable balance (or part of it) to payouts:in-transit.
     * Signs: the payable account is a liability (credit normal). Paying the brand reduces what we owe, which
     * is a debit, so payable gets +amount. payouts:in-transit is also a liability here (money we have released
     * to the bank but that has not settled yet), so it gets the credit, -amount, and its normal balance grows.
     * The postings sum to 0 and the no-overdraft rule on payable rejects anything above the balance.
     */
    @Transactional
    public Result payout(String key, PayoutRequest request) {
        if (request.amount() != null && request.amount() <= 0) {
            throw LedgerException.invalid("Payout amount must be positive");
        }
        return idempotent(key, "POST /v1/payouts " + toJson(request), entryId -> {
            String payableId = "brand:" + request.brandId() + ":payable";
            Account payable = accounts.lockAll(List.of(payableId)).stream().findFirst()
                    .orElseThrow(() -> LedgerException.notFound("Brand " + request.brandId() + " has no payable account"));
            long available = payable.normalBalance();
            long amount = request.amount() != null ? request.amount() : available;
            if (amount <= 0) {
                throw LedgerException.invalid("Brand " + request.brandId() + " has nothing to pay out");
            }
            if (amount > available) {
                throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds",
                        "Payout " + amount + " exceeds payable balance " + available);
            }
            List<PostingRequest> postings = List.of(
                    new PostingRequest(payableId, amount, payable.currency()),
                    new PostingRequest(IN_TRANSIT, -amount, payable.currency()));
            return post(entryId, "Payout to brand " + request.brandId(), null, null, request.reference(), postings,
                    TransferValidator.validate(postings));
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

    /**
     * Closes a payout once the bank statement shows the money left the bank: payouts:in-transit is debited (we no
     * longer owe the bank run) and cash:bank is credited (the money is gone). Returns empty if the entry is not a
     * payout, was reversed, or is already settled. The payout row is locked first, so two reconciliations running
     * at the same time settle it once; the unique settles_entry_id column is the second line of defence.
     * Runs inside the caller's transaction, which must lock payouts in ascending id order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TransferResponse> settlePayout(UUID payoutId) {
        // NO KEY UPDATE, not UPDATE: it does not block reversals, whose foreign key only takes KEY SHARE.
        Optional<String> reference = jdbc.sql("SELECT reference FROM journal_entries WHERE id = :id FOR NO KEY UPDATE")
                .param("id", payoutId).query((rs, i) -> Optional.ofNullable(rs.getString(1))).optional()
                .orElse(Optional.empty());
        boolean done = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM journal_entries WHERE settles_entry_id = :id OR reverses_entry_id = :id)
                """).param("id", payoutId).query(Boolean.class).single();
        if (done) {
            return Optional.empty();
        }
        Optional<PostingRequest> inTransit = jdbc.sql("""
                SELECT amount, currency FROM postings
                WHERE entry_id = :id AND account_id = 'payouts:in-transit' AND amount < 0
                """)
                .param("id", payoutId)
                .query((rs, i) -> new PostingRequest(IN_TRANSIT, -rs.getLong("amount"), rs.getString("currency")))
                .optional();
        if (inTransit.isEmpty()) {
            return Optional.empty();
        }
        PostingRequest debit = inTransit.get();
        List<PostingRequest> postings = List.of(debit, new PostingRequest(BANK, -debit.amount(), debit.currency()));
        return Optional.of(post(UUID.randomUUID(), "Payout settled at the bank", null, payoutId, reference.orElse(null),
                postings, TransferValidator.validate(postings)));
    }

    private TransferResponse post(UUID entryId, String description, UUID reverses, UUID settles, String reference,
                                  List<PostingRequest> postings,
                                  Map<String, Long> deltas) {
        // deltas is sorted by account id, and lockAll orders by id as well.
        Map<String, Account> locked = accounts.lockAll(deltas.keySet()).stream()
                .collect(Collectors.toMap(Account::id, a -> a));
        Map<String, String> currencyByAccount = postings.stream()
                .collect(Collectors.toMap(PostingRequest::accountId, PostingRequest::currency, (a, b) -> a));
        for (var e : deltas.entrySet()) {
            Account account = locked.get(e.getKey());
            if (account == null) {
                throw LedgerException.invalid("Account " + e.getKey() + " does not exist");
            }
            String currency = currencyByAccount.get(e.getKey());
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
            jdbc.sql("INSERT INTO journal_entries (id, description, reverses_entry_id, settles_entry_id, reference, created_at) "
                            + "VALUES (:id, :description, :reverses, :settles, :reference, :createdAt)")
                    .param("id", entryId).param("description", description).param("reverses", reverses)
                    .param("settles", settles).param("reference", reference).param("createdAt", Timestamp.from(createdAt))
                    .update();
        } catch (DuplicateKeyException e) {
            if (settles != null) {
                throw LedgerException.conflict("Already settled", "Payout " + settles + " was already settled");
            }
            throw alreadyReversed(reverses);
        }
        for (PostingRequest p : postings) {
            jdbc.sql("INSERT INTO postings (entry_id, account_id, amount, currency) VALUES (:e, :a, :amount, :c)")
                    .param("e", entryId).param("a", p.accountId()).param("amount", p.amount()).param("c", p.currency())
                    .update();
        }
        deltas.forEach(accounts::applyDelta);
        TransferResponse response = new TransferResponse(entryId, description, reverses, createdAt, postings, reference);
        // Transactional outbox: same transaction as the entry, so an entry is published if and only if it exists.
        jdbc.sql("INSERT INTO outbox (entry_id, topic, payload) VALUES (:id, 'ledger.entries', CAST(:payload AS jsonb))")
                .param("id", entryId).param("payload", toJson(response)).update();
        return response;
    }

    private static LedgerException alreadyReversed(UUID entryId) {
        return LedgerException.conflict("Already reversed", "Entry " + entryId + " was already reversed");
    }

    private String toJson(Object value) {
        return json.writeValueAsString(value);
    }

    private TransferResponse fromJson(String value) {
        return json.readValue(value, TransferResponse.class);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
