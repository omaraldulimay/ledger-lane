package dev.ledgerlane.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.ledgerlane.PostgresTestBase;
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
class LedgerApiTest extends PostgresTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    String cash, payable, revenue;

    @BeforeEach
    void accounts() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        cash = "cash:" + run;
        payable = "brand:" + run + ":payable";
        revenue = "revenue:" + run;
        createAccount(cash, "asset", false);
        createAccount(payable, "liability", false);
        createAccount(revenue, "revenue", false);
    }

    @Test
    void transferUpdatesBalancesAndNormalBalances() throws Exception {
        sale(10000, "k-" + UUID.randomUUID()).andExpect(status().isCreated());

        mvc.perform(get("/v1/accounts/" + cash)).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(10000))
                .andExpect(jsonPath("$.normalBalance").value(10000))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/v1/accounts/" + payable))
                .andExpect(jsonPath("$.balance").value(-8500))
                .andExpect(jsonPath("$.normalBalance").value(8500));
        assertInvariants();
    }

    @Test
    void replayWithSameKeyAndBodyReturnsOriginalResponse() throws Exception {
        String key = "k-" + UUID.randomUUID();
        String first = sale(10000, key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = sale(10000, key).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(second)).isEqualTo(json.readTree(first));
        mvc.perform(get("/v1/accounts/" + cash)).andExpect(jsonPath("$.balance").value(10000));
    }

    @Test
    void sameKeyWithDifferentBodyIsConflict() throws Exception {
        String key = "k-" + UUID.randomUUID();
        sale(10000, key).andExpect(status().isCreated());
        sale(20000, key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Idempotency key reused"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void missingIdempotencyKeyIsBadRequest() throws Exception {
        mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(saleBody(100)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void decimalAmountIsRejected() throws Exception {
        String body = saleBody(100).replace("100", "100.5");
        mvc.perform(post("/v1/transfers").header("Idempotency-Key", "k-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
    }

    @Test
    void unbalancedTransferIsUnprocessable() throws Exception {
        String body = """
                {"description":"bad","postings":[
                 {"accountId":"%s","amount":100,"currency":"EUR"},
                 {"accountId":"%s","amount":-90,"currency":"EUR"}]}""".formatted(cash, revenue);
        transfer(body, "k-" + UUID.randomUUID()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknownAccountIsUnprocessable() throws Exception {
        String body = """
                {"description":"bad","postings":[
                 {"accountId":"%s","amount":100,"currency":"EUR"},
                 {"accountId":"nope:%s","amount":-100,"currency":"EUR"}]}""".formatted(cash, cash);
        transfer(body, "k-" + UUID.randomUUID()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void overdraftOnNormalBalanceIsRejectedAndNothingChanges() throws Exception {
        // Paying out from the brand payable (credit normal) without funds would make it negative.
        String body = """
                {"description":"payout","postings":[
                 {"accountId":"%s","amount":500,"currency":"EUR"},
                 {"accountId":"%s","amount":-500,"currency":"EUR"}]}""".formatted(payable, cash);
        transfer(body, "k-" + UUID.randomUUID()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Insufficient funds"));
        // cash would go negative too, so the failed attempt must leave both at zero
        mvc.perform(get("/v1/accounts/" + payable)).andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.version").value(0));
        // a rejected request does not burn its key
        sale(10000, "k-" + UUID.randomUUID()).andExpect(status().isCreated());
    }

    @Test
    void reversalRestoresBalancesIsIdempotentAndWorksOnlyOnce() throws Exception {
        String id = id(sale(10000, "k-" + UUID.randomUUID()).andExpect(status().isCreated()));
        String key = "r-" + UUID.randomUUID();

        mvc.perform(post("/v1/transfers/" + id + "/reversal").header("Idempotency-Key", key))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.reversesEntryId").value(id));
        mvc.perform(post("/v1/transfers/" + id + "/reversal").header("Idempotency-Key", key))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/transfers/" + id + "/reversal").header("Idempotency-Key", "r-" + UUID.randomUUID()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Already reversed"));
        mvc.perform(post("/v1/transfers/" + UUID.randomUUID() + "/reversal").header("Idempotency-Key", "r-" + UUID.randomUUID()))
                .andExpect(status().isNotFound());

        mvc.perform(get("/v1/accounts/" + cash)).andExpect(jsonPath("$.balance").value(0));
        mvc.perform(get("/v1/accounts/" + payable)).andExpect(jsonPath("$.balance").value(0));
        assertInvariants();
    }

    @Test
    void postingsArePaginatedNewestFirst() throws Exception {
        for (int i = 1; i <= 5; i++) {
            sale(i * 100, "k-" + UUID.randomUUID()).andExpect(status().isCreated());
        }
        JsonNode page1 = body(mvc.perform(get("/v1/accounts/" + cash + "/postings?limit=2")));
        assertThat(page1.get("items")).extracting(n -> n.get("amount").asLong()).containsExactly(500L, 400L);
        JsonNode page2 = body(mvc.perform(get("/v1/accounts/" + cash + "/postings?limit=2&cursor=" + page1.get("nextCursor").asText())));
        assertThat(page2.get("items")).extracting(n -> n.get("amount").asLong()).containsExactly(300L, 200L);
        JsonNode page3 = body(mvc.perform(get("/v1/accounts/" + cash + "/postings?limit=2&cursor=" + page2.get("nextCursor").asText())));
        assertThat(page3.get("items")).extracting(n -> n.get("amount").asLong()).containsExactly(100L);
        assertThat(page3.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void entriesAreImmutableInTheDatabase() throws Exception {
        sale(10000, "k-" + UUID.randomUUID());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.sql("UPDATE postings SET amount = amount + 1 WHERE account_id = :a").param("a", cash).update())
                .hasMessageContaining("immutable");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.sql("DELETE FROM journal_entries").update())
                .hasMessageContaining("immutable");
    }

    @Test
    void duplicateAccountIsConflict() throws Exception {
        mvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"%s\",\"type\":\"asset\",\"currency\":\"EUR\"}".formatted(cash)))
                .andExpect(status().isConflict());
    }

    // helpers

    private void createAccount(String id, String type, boolean allowNegative) throws Exception {
        mvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"%s\",\"type\":\"%s\",\"currency\":\"EUR\",\"allowNegative\":%s}"
                                .formatted(id, type, allowNegative)))
                .andExpect(status().isCreated());
    }

    private String saleBody(long gross) {
        long commission = gross * 15 / 100;
        return """
                {"description":"sale","postings":[
                 {"accountId":"%s","amount":%d,"currency":"EUR"},
                 {"accountId":"%s","amount":%d,"currency":"EUR"},
                 {"accountId":"%s","amount":%d,"currency":"EUR"}]}"""
                .formatted(cash, gross, payable, -(gross - commission), revenue, -commission);
    }

    private ResultActions sale(long gross, String key) throws Exception {
        return transfer(saleBody(gross), key);
    }

    private ResultActions transfer(String body, String key) throws Exception {
        return mvc.perform(post("/v1/transfers").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions result) throws Exception {
        return body(result).get("id").asText();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private void assertInvariants() {
        InvariantChecks.assertHold(jdbc);
    }
}
