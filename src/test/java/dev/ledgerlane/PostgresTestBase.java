package dev.ledgerlane;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Uses a Testcontainers Postgres when Docker is available. Otherwise it connects to the database in
 * LEDGER_TEST_DB_URL (with LEDGER_TEST_DB_USER and LEDGER_TEST_DB_PASSWORD), see scripts/local-db.sh.
 */
@SpringBootTest
public abstract class PostgresTestBase {

    private static final PostgreSQLContainer CONTAINER =
            DockerClientFactory.instance().isDockerAvailable() ? new PostgreSQLContainer("postgres:16-alpine") : null;

    static {
        if (CONTAINER != null) {
            CONTAINER.start();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        // tests drive the relay by hand
        registry.add("ledger.outbox.relay.enabled", () -> "false");
        if (CONTAINER != null) {
            registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
            registry.add("spring.datasource.username", CONTAINER::getUsername);
            registry.add("spring.datasource.password", CONTAINER::getPassword);
            return;
        }
        String url = System.getenv("LEDGER_TEST_DB_URL");
        if (url == null) {
            throw new IllegalStateException("No Docker found and LEDGER_TEST_DB_URL is not set");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("LEDGER_TEST_DB_USER", "ledger"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("LEDGER_TEST_DB_PASSWORD", "ledger"));
    }
}
