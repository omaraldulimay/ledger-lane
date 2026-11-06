package dev.ledgerlane.outbox;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claims unpublished rows with FOR UPDATE SKIP LOCKED, so several relays never take the same row, publishes
 * them in id order and marks them. A crash between publish and commit publishes the row again: at least once.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(long id, String entryId, String topic, String payload) {
    }

    private final JdbcClient jdbc;
    private final OutboxPublisher publisher;
    private final TransactionTemplate tx;
    private final int batchSize;

    public OutboxRelay(JdbcClient jdbc, OutboxPublisher publisher, PlatformTransactionManager txManager,
                       @Value("${ledger.outbox.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.tx = new TransactionTemplate(txManager);
        this.batchSize = batchSize;
    }

    /** Publishes one batch and returns how many rows were published. A failure stops the batch. */
    public int runOnce() {
        Integer published = tx.execute(status -> {
            List<Row> rows = jdbc.sql("""
                    SELECT id, entry_id::text AS entry_id, topic, payload::text AS payload FROM outbox
                    WHERE published_at IS NULL ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED
                    """)
                    .param("limit", batchSize)
                    .query((rs, i) -> new Row(rs.getLong("id"), rs.getString("entry_id"), rs.getString("topic"),
                            rs.getString("payload")))
                    .list();
            int done = 0;
            for (Row row : rows) {
                try {
                    publisher.publish(row.topic(), row.entryId(), row.payload());
                } catch (Exception e) {
                    log.warn("publishing outbox row {} failed, will retry: {}", row.id(), e.toString());
                    break; // keep order, the rest stays unpublished
                }
                jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = :id").param("id", row.id()).update();
                done++;
            }
            return done;
        });
        return published == null ? 0 : published;
    }
}
