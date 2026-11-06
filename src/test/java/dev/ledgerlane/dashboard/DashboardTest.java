package dev.ledgerlane.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ledgerlane.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class DashboardTest extends PostgresTestBase {

    @Autowired MockMvc mvc;

    @Test
    void reportsBalancedLedger() throws Exception {
        mvc.perform(get("/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postingsSumCents").value(0))
                .andExpect(jsonPath("$.driftedAccounts").value(0))
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.outboxLag").isNumber())
                .andExpect(jsonPath("$.accounts").isArray());
    }
}
