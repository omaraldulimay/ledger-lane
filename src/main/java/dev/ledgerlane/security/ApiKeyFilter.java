package dev.ledgerlane.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the X-Api-Key header, hashes it and looks the hash up in the configured keys. A request without a
 * known key stays anonymous, and the authorization rules turn that into 401. Hashes are compared in constant
 * time so the response time does not reveal how much of a key was right.
 */
class ApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Api-Key";

    private final List<ApiKeyProperties.Key> keys;

    ApiKeyFilter(ApiKeyProperties properties) {
        this.keys = properties.apiKeys();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented != null && !presented.isBlank()) {
            byte[] hash = HexFormat.of().formatHex(sha256(presented.trim())).getBytes(StandardCharsets.US_ASCII);
            ApiKeyProperties.Key match = null;
            for (ApiKeyProperties.Key key : keys) {
                if (MessageDigest.isEqual(hash, key.sha256().toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
                    match = key;
                }
            }
            if (match != null) {
                var authorities = match.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(match.name(), null, authorities));
            }
        }
        chain.doFilter(request, response);
    }

    static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
