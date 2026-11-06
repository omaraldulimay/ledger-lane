package dev.ledgerlane.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlane.PostgresTestBase;
import dev.ledgerlane.account.AccountRepository;
import dev.ledgerlane.account.AccountType;
import dev.ledgerlane.transfer.PostingRequest;
import dev.ledgerlane.transfer.TransferRequest;
import dev.ledgerlane.transfer.TransferService;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** Needs Docker: skipped locally without it, runs in CI. */
@Testcontainers(disabledWithoutDocker = true)
class KafkaOutboxTest extends PostgresTestBase {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("ledger.outbox.publisher", () -> "kafka");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired TransferService service;
    @Autowired AccountRepository accounts;
    @Autowired OutboxRelay relay;

    @Test
    void everyEntryArrivesOnTheTopic() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        accounts.insert("a:" + run, AccountType.ASSET, "EUR", true);
        accounts.insert("b:" + run, AccountType.ASSET, "EUR", true);
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            expected.add(service.transfer("k-" + UUID.randomUUID(), new TransferRequest("t" + i, List.of(
                    new PostingRequest("a:" + run, 100L + i, "EUR"),
                    new PostingRequest("b:" + run, -(100L + i), "EUR")))).transfer().id().toString());
        }
        while (relay.runOnce() > 0) {
            // drain
        }

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + run);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        Set<String> received = new HashSet<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("ledger.entries"));
            long deadline = System.currentTimeMillis() + 30_000;
            while (!received.containsAll(expected) && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    received.add(r.key());
                    assertThat(r.value()).contains(r.key());
                }
            }
        }
        assertThat(received).containsAll(expected);
    }
}
