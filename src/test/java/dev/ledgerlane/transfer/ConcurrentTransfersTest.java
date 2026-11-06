package dev.ledgerlane.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlane.PostgresTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ConcurrentTransfersTest extends PostgresTestBase {

    private static final int THREADS = 8;
    private static final int TRANSFERS_PER_THREAD = 200;

    @Autowired TransferService service;
    @Autowired JdbcClient jdbc;
    @Autowired dev.ledgerlane.account.AccountRepository accounts;

    @Test
    void parallelTransfersKeepTheBooksBalanced() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        List<String> ids = List.of("a:" + run, "b:" + run, "c:" + run, "d:" + run);
        // allowNegative so the test exercises locking, not the overdraft rule
        ids.forEach(id -> accounts.insert(id, dev.ledgerlane.account.AccountType.ASSET, "EUR", true));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            int thread = t;
            Callable<Integer> work = () -> {
                start.await();
                for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                    // Opposite directions between the same accounts: the classic deadlock setup.
                    String from = ids.get((thread + i) % ids.size());
                    String to = ids.get((thread + i + 1 + i % 3) % ids.size());
                    if (from.equals(to)) {
                        to = ids.get((ids.indexOf(to) + 1) % ids.size());
                    }
                    long amount = 1 + (i % 50);
                    service.transfer("c-" + UUID.randomUUID(), new TransferRequest("move", List.of(
                            new PostingRequest(from, amount, "EUR"), new PostingRequest(to, -amount, "EUR"))));
                }
                return TRANSFERS_PER_THREAD;
            };
            futures.add(pool.submit(work));
        }
        start.countDown();
        int done = 0;
        for (Future<Integer> f : futures) {
            done += f.get();
        }
        pool.shutdown();

        assertThat(done).isEqualTo(THREADS * TRANSFERS_PER_THREAD);
        Long entries = jdbc.sql("SELECT COUNT(DISTINCT p.entry_id) FROM postings p WHERE p.account_id IN (:ids)")
                .param("ids", ids).query(Long.class).single();
        assertThat(entries).isEqualTo(THREADS * TRANSFERS_PER_THREAD);
        Long sum = jdbc.sql("SELECT SUM(balance) FROM accounts WHERE id IN (:ids)").param("ids", ids).query(Long.class).single();
        assertThat(sum).isZero();
        InvariantChecks.assertHold(jdbc);
    }
}
