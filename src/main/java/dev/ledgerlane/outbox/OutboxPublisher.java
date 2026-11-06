package dev.ledgerlane.outbox;

/** Sends one outbox message. Delivery is at least once, so consumers dedupe by entry id (the key). */
public interface OutboxPublisher {

    /** Must return only after the message is safely handed over, and throw if it is not. */
    void publish(String topic, String key, String payload) throws Exception;
}
