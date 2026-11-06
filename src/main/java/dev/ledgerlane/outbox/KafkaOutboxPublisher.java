package dev.ledgerlane.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Producer settings (acks=all, idempotent producer) are in application.yml. send().get() blocks until the
 * broker acknowledged, only then does the relay mark the row as published.
 */
@Component
@ConditionalOnProperty(name = "ledger.outbox.publisher", havingValue = "kafka")
class KafkaOutboxPublisher implements OutboxPublisher {

    private final KafkaTemplate<String, String> kafka;

    KafkaOutboxPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publish(String topic, String key, String payload) throws Exception {
        kafka.send(topic, key, payload).get();
    }
}
