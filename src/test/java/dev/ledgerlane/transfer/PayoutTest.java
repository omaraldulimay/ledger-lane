package dev.ledgerlane.transfer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ledgerlane.PostgresTestBase;
import dev.ledgerlane.account.AccountRepository;
import dev.ledgerlane.account.AccountType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class PayoutTest extends PostgresTestBase {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AccountRepository accounts;
    @Autowired TransferService service;

    String brand, payable;

    @BeforeEach
    void setUp() {
        brand = UUID.randomUUID().toString().substring(0, 8);
        payable = "brand:" + brand + ":payable";
        accounts.insert("cash:bank", AccountType.ASSET, "EUR", true);
        accounts.insert("payouts:in-transit", AccountType.LIABILITY, "EUR", false);
        accounts.insert(payable, AccountType.LIABILITY, "EUR", false);
        service.transfer("k-" + UUID.randomUUID(), new TransferRequest("sale", List.of(
                new PostingRequest("cash:bank", 10000L, "EUR"), new PostingRequest(payable, -10000L, "EUR"))));
    }

    @Test
    void partialAndFullPayout() throws Exception {
        payout("{\"brandId\":\"%s\",\"amount\":4000}", "p-" + UUID.randomUUID()).andExpect(status().isCreated());
        mvc.perform(get("/v1/accounts/" + payable)).andExpect(jsonPath("$.normalBalance").value(6000));

        payout("{\"brandId\":\"%s\"}", "p-" + UUID.randomUUID()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.postings[0].amount").value(6000))
                .andExpect(jsonPath("$.postings[1].accountId").value("payouts:in-transit"));
        mvc.perform(get("/v1/accounts/" + payable)).andExpect(jsonPath("$.normalBalance").value(0));
        InvariantChecks.assertHold(jdbc);
    }

    @Test
    void replayWithSameKeyPaysOnce() throws Exception {
        String key = "p-" + UUID.randomUUID();
        payout("{\"brandId\":\"%s\",\"amount\":2500}", key).andExpect(status().isCreated());
        payout("{\"brandId\":\"%s\",\"amount\":2500}", key).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        payout("{\"brandId\":\"%s\",\"amount\":2600}", key).andExpect(status().isConflict());
        mvc.perform(get("/v1/accounts/" + payable)).andExpect(jsonPath("$.normalBalance").value(7500));
        InvariantChecks.assertHold(jdbc);
    }

    @Test
    void overPayoutAndZeroAreUnprocessable() throws Exception {
        payout("{\"brandId\":\"%s\",\"amount\":10001}", "p-" + UUID.randomUUID())
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.title").value("Insufficient funds"));
        payout("{\"brandId\":\"%s\",\"amount\":0}", "p-" + UUID.randomUUID()).andExpect(status().isUnprocessableEntity());
        // empty balance, no amount
        payout("{\"brandId\":\"%s\"}", "p-" + UUID.randomUUID()).andExpect(status().isCreated());
        payout("{\"brandId\":\"%s\"}", "p-" + UUID.randomUUID()).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/v1/payouts").contentType(MediaType.APPLICATION_JSON).content("{\"brandId\":\"x\"}"))
                .andExpect(status().isBadRequest());
        InvariantChecks.assertHold(jdbc);
    }

    private ResultActions payout(String body, String key) throws Exception {
        return mvc.perform(post("/v1/payouts").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted(brand)));
    }
}
