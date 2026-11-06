package dev.ledgerlane.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default publisher for local runs without Kafka. */
@Component
@ConditionalOnProperty(name = "ledger.outbox.publisher", havingValue = "log", matchIfMissing = true)
class LoggingOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxPublisher.class);

    @Override
    public void publish(String topic, String key, String payload) {
        log.info("outbox {} key={} {}", topic, key, payload);
    }
}
