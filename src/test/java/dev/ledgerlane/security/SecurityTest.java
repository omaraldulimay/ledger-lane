package dev.ledgerlane.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ledgerlane.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class SecurityTest extends PostgresTestBase {

    @Autowired MockMvc mvc;

    @Test
    void requestWithoutKeyIsUnauthorizedAsProblemJson() throws Exception {
        // an empty header overrides the default test key
        mvc.perform(get("/v1/dashboard").header("X-Api-Key", ""))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void unknownKeyIsUnauthorized() throws Exception {
        mvc.perform(get("/v1/dashboard").header("X-Api-Key", "guessed")).andExpect(status().isUnauthorized());
    }

    @Test
    void shopMayPostTransfersButNotReadOrPayOut() throws Exception {
        // 400 (no Idempotency-Key) proves the request got past security
        mvc.perform(post("/v1/transfers").header("X-Api-Key", SHOP_KEY).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/dashboard").header("X-Api-Key", SHOP_KEY)).andExpect(status().isForbidden());
        mvc.perform(post("/v1/payouts").header("X-Api-Key", SHOP_KEY).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    void auditorMayOnlyRead() throws Exception {
        mvc.perform(get("/v1/dashboard").header("X-Api-Key", AUDITOR_KEY)).andExpect(status().isOk());
        mvc.perform(post("/v1/transfers").header("X-Api-Key", AUDITOR_KEY).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/reconciliations").header("X-Api-Key", AUDITOR_KEY).contentType("text/csv").content("x"))
                .andExpect(status().isForbidden());
    }

    @Test
    void leitstandPageHealthAndApiDocsArePublic() throws Exception {
        mvc.perform(get("/").header("X-Api-Key", "")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health").header("X-Api-Key", "")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs").header("X-Api-Key", "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.name").value("X-Api-Key"));
        mvc.perform(get("/actuator/prometheus").header("X-Api-Key", "")).andExpect(status().isUnauthorized());
    }
}
