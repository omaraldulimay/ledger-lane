package dev.ledgerlane.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlane.PostgresTestBase;
import dev.ledgerlane.account.AccountRepository;
import dev.ledgerlane.account.AccountType;
import dev.ledgerlane.transfer.PostingRequest;
import dev.ledgerlane.transfer.TransferRequest;
import dev.ledgerlane.transfer.TransferService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

class OutboxTest extends PostgresTestBase {

    @Autowired TransferService service;
    @Autowired JdbcClient jdbc;
    @Autowired AccountRepository accounts;
    @Autowired PlatformTransactionManager txManager;

    String a, b;

    @BeforeEach
    void accounts() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        a = "a:" + run;
        b = "b:" + run;
        accounts.insert(a, AccountType.ASSET, "EUR", true);
        accounts.insert(b, AccountType.ASSET, "EUR", true);
    }

    @Test
    void everyEntryHasExactlyOneOutboxRow() {
        UUID first = transfer();
        UUID reversal = service.reverse("r-" + UUID.randomUUID(), first).transfer().id();
        for (UUID id : List.of(first, reversal)) {
            assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox WHERE entry_id = :id").param("id", id)
                    .query(Long.class).single()).isEqualTo(1);
        }
        String payload = jdbc.sql("SELECT payload::text FROM outbox WHERE entry_id = :id").param("id", reversal)
                .query(String.class).single();
        assertThat(payload).contains(reversal.toString(), first.toString(), "postings", "createdAt", "description");
        // global: no entry without a row, no entry with two
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM journal_entries e
                WHERE (SELECT COUNT(*) FROM outbox o WHERE o.entry_id = e.id) <> 1
                """).query(Long.class).single()).isZero();
    }

    @Test
    void relayPublishesAndMarksRows() {
        UUID id = transfer();
        List<String> keys = new ArrayList<>();
        OutboxRelay relay = new OutboxRelay(jdbc, (topic, key, payload) -> keys.add(key), txManager, 50);
        while (relay.runOnce() > 0) {
            // drain
        }
        assertThat(keys).contains(id.toString());
        assertThat(published(id)).isTrue();
        assertThat(relay.runOnce()).isZero();
    }

    @Test
    void failureLeavesRowsUnpublishedAndTheyAreRetried() {
        UUID id = transfer();
        AtomicBoolean down = new AtomicBoolean(true);
        List<String> keys = Collections.synchronizedList(new ArrayList<>());
        OutboxPublisher flaky = (topic, key, payload) -> {
            if (down.get()) {
                throw new IllegalStateException("broker down");
            }
            keys.add(key);
        };
        OutboxRelay relay = new OutboxRelay(jdbc, flaky, txManager, 50);

        assertThat(relay.runOnce()).isZero();
        assertThat(published(id)).isFalse();

        down.set(false);
        while (relay.runOnce() > 0) {
            // drain
        }
        assertThat(published(id)).isTrue();
        assertThat(keys).containsOnlyOnce(id.toString());
    }

    @Test
    void parallelRelaysNeverPublishTheSameRowTwice() throws Exception {
        for (int i = 0; i < 60; i++) {
            transfer();
        }
        List<String> keys = Collections.synchronizedList(new ArrayList<>());
        OutboxPublisher slow = (topic, key, payload) -> {
            Thread.sleep(2);
            keys.add(key);
        };
        int relays = 4;
        ExecutorService pool = Executors.newFixedThreadPool(relays);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < relays; i++) {
            OutboxRelay relay = new OutboxRelay(jdbc, slow, txManager, 7);
            futures.add(pool.submit(() -> {
                start.await();
                while (relay.runOnce() > 0) {
                    // drain
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(keys).hasSizeGreaterThanOrEqualTo(60).doesNotHaveDuplicates();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single()).isZero();
    }

    private UUID transfer() {
        return service.transfer("k-" + UUID.randomUUID(), new TransferRequest("t", List.of(
                new PostingRequest(a, 100L, "EUR"), new PostingRequest(b, -100L, "EUR")))).transfer().id();
    }

    private boolean published(UUID id) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM outbox WHERE entry_id = :id").param("id", id)
                .query(Boolean.class).single();
    }
}
