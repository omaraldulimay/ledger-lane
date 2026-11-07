package dev.ledgerlane.security;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Stateless API key authentication with three roles. CSRF protection is off on purpose: there are no cookies
 * or sessions, the key travels in a header that a foreign site cannot make the browser send.
 */
@Configuration
@EnableConfigurationProperties(ApiKeyProperties.class)
@OpenAPIDefinition(info = @Info(title = "Ledger Lane", version = "v1"),
        security = @SecurityRequirement(name = "apiKey"))
@SecurityScheme(name = "apiKey", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = ApiKeyFilter.HEADER)
class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String SHOP = "SHOP", FINANCE = "FINANCE", AUDITOR = "AUDITOR";

    @Bean
    SecurityFilterChain api(HttpSecurity http, ApiKeyProperties properties) throws Exception {
        if (properties.apiKeys().isEmpty()) {
            log.warn("No API keys configured (ledger.security.api-keys): every /v1 request will be rejected");
        }
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .addFilterBefore(new ApiKeyFilter(properties), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/error").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/transfers").hasRole(SHOP)
                        .requestMatchers(HttpMethod.POST, "/v1/transfers/*/reversal").hasAnyRole(SHOP, FINANCE)
                        .requestMatchers(HttpMethod.POST, "/v1/payouts", "/v1/reconciliations", "/v1/accounts")
                            .hasRole(FINANCE)
                        .requestMatchers(HttpMethod.GET, "/v1/**").hasAnyRole(FINANCE, AUDITOR)
                        .requestMatchers("/actuator/**").hasAnyRole(FINANCE, AUDITOR)
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problem(res, 401, "Unauthorized",
                                "Send a valid API key in the X-Api-Key header"))
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403, "Forbidden",
                                "This API key may not " + req.getMethod() + " " + req.getRequestURI())));
        return http.build();
    }

    /** RFC 9457 problem+json, like every other error of the API. */
    private static void problem(HttpServletResponse res, int status, String title, String detail) throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    }
}
