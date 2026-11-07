package dev.ledgerlane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Uses a Testcontainers Postgres when Docker is available. Otherwise it connects to the database in
 * LEDGER_TEST_DB_URL (with LEDGER_TEST_DB_USER and LEDGER_TEST_DB_PASSWORD), see scripts/local-db.sh.
 */
@SpringBootTest
@Import(PostgresTestBase.DefaultApiKey.class)
public abstract class PostgresTestBase {

    /** Key with every role, sent by MockMvc unless a test sets its own X-Api-Key header. */
    public static final String ALL_ROLES_KEY = "test-all";
    public static final String SHOP_KEY = "test-shop";
    public static final String AUDITOR_KEY = "test-auditor";

    @TestConfiguration
    static class DefaultApiKey {
        @Bean
        MockMvcBuilderCustomizer defaultApiKey() {
            return builder -> builder.defaultRequest(get("/").header("X-Api-Key", ALL_ROLES_KEY));
        }
    }

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
        key(registry, 0, ALL_ROLES_KEY, "8a1048a3ca22c1a651cbb0b363a7065b9067ffd8720325b6a8daba1b65a75fb2", "SHOP,FINANCE,AUDITOR");
        key(registry, 1, SHOP_KEY, "550389c6146bb37156bd181a104e24d2ab58aca94fd264c06cc6039743483291", "SHOP");
        key(registry, 2, AUDITOR_KEY, "5b53323211991d13cc20d107cdb4e4cd5bd367e016a950b5735e4e3ac84b538c", "AUDITOR");
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

    private static void key(DynamicPropertyRegistry registry, int i, String name, String sha256, String roles) {
        String p = "ledger.security.api-keys[" + i + "].";
        registry.add(p + "name", () -> name);
        registry.add(p + "sha256", () -> sha256);
        registry.add(p + "roles", () -> roles);
    }
}
