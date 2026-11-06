package dev.ledgerlane.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the relay on a timer. Tests switch it off with ledger.outbox.relay.enabled=false. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "ledger.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxScheduler {

    private final OutboxRelay relay;

    OutboxScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.relay.interval-ms:500}")
    void tick() {
        while (relay.runOnce() > 0) {
            // drain the backlog batch by batch
        }
    }
}
