package dev.ledgerlane.security;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API keys from configuration. Only the SHA-256 hash of a key is stored, never the key itself.
 * Roles: SHOP posts customer payments and reversals, FINANCE pays out, reconciles and reads,
 * AUDITOR only reads.
 */
@ConfigurationProperties("ledger.security")
public record ApiKeyProperties(List<Key> apiKeys) {

    public ApiKeyProperties {
        apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
    }

    public record Key(String name, String sha256, Set<Role> roles) {
    }

    public enum Role { SHOP, FINANCE, AUDITOR }
}
