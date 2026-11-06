package dev.ledgerlane.reconciliation;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ledgerlane.PostgresTestBase;
import dev.ledgerlane.account.AccountRepository;
import dev.ledgerlane.account.AccountType;
import dev.ledgerlane.transfer.PayoutRequest;
import dev.ledgerlane.transfer.PostingRequest;
import dev.ledgerlane.transfer.TransferRequest;
import dev.ledgerlane.transfer.TransferService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ReconciliationApiTest extends PostgresTestBase {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired TransferService service;
    @Autowired ReconciliationService reconciliation;

    @Test
    void reconcilesPaymentsAndPayoutsAndReportsDiscrepancies() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        String brand = run;
        String payable = "brand:" + brand + ":payable";
        accounts.insert("cash:bank", AccountType.ASSET, "EUR", true);
        accounts.insert("payouts:in-transit", AccountType.LIABILITY, "EUR", false);
        accounts.insert(payable, AccountType.LIABILITY, "EUR", false);

        String ok = "ok-" + run, wrong = "wrong-" + run, gone = "gone-" + run, payout = "po-" + run;
        sale(ok, 10000, payable);
        sale(wrong, 5000, payable);
        sale(gone, 2000, payable);
        service.payout("k-" + UUID.randomUUID(), new PayoutRequest(brand, 4000L, payout));

        String csv = """
                booking_date,amount_cents,reference,counterparty
                2026-11-02,10000,%s,Customer A
                2026-11-02,5001,%s,Customer B
                2026-11-03,-4000,%s,Brand payout
                2026-11-03,-35,,Bank fee
                """.formatted(ok, wrong, payout);

        mvc.perform(post("/v1/reconciliations").contentType("text/csv").content(csv.getBytes()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").isNumber())
                .andExpect(jsonPath("$.missingInLedger[*].counterparty").value(hasItem("Bank fee")))
                .andExpect(jsonPath("$.missingAtBank[*].reference").value(hasItem(gone)))
                .andExpect(jsonPath("$.amountMismatch[*].reference").value(hasItem(wrong)))
                .andExpect(jsonPath("$.amountMismatch[?(@.reference=='" + wrong + "')].bankAmountCents").value(hasItem(5001)))
                .andExpect(jsonPath("$.amountMismatch[?(@.reference=='" + wrong + "')].ledgerAmountCents").value(hasItem(5000)));

        // the payout and the exact sale were matched, so they appear in no discrepancy list
        mvc.perform(post("/v1/reconciliations").contentType("text/csv").content(csv.getBytes()))
                .andExpect(jsonPath("$.missingAtBank[*].reference").value(org.hamcrest.Matchers.not(hasItem(payout))))
                .andExpect(jsonPath("$.missingAtBank[*].reference").value(org.hamcrest.Matchers.not(hasItem(ok))));
        org.assertj.core.api.Assertions.assertThat(reconciliation.latest()).isPresent();
    }

    @Test
    void rejectsMalformedCsv() throws Exception {
        mvc.perform(post("/v1/reconciliations").contentType("text/csv").content("a,b\n1,2".getBytes()))
                .andExpect(status().is4xxClientError());
    }

    private void sale(String reference, long cents, String payable) {
        service.transfer("k-" + UUID.randomUUID(), new TransferRequest("sale " + reference, List.of(
                new PostingRequest("cash:bank", cents, "EUR"), new PostingRequest(payable, -cents, "EUR")), reference));
    }
}
