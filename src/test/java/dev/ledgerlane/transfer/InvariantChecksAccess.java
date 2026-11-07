package dev.ledgerlane.transfer;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Lets tests in other packages run the invariant checks. */
public final class InvariantChecksAccess {

    private InvariantChecksAccess() {
    }

    public static void assertHold(JdbcClient jdbc) {
        InvariantChecks.assertHold(jdbc);
    }
}
