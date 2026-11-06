package dev.ledgerlane.reconciliation;

import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/reconciliations")
class ReconciliationController {

    private final ReconciliationService service;

    ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    /** Body is the bank statement as text/csv (UTF-8). */
    @PostMapping(consumes = "text/csv")
    ReconciliationResult reconcile(@RequestBody byte[] csv) {
        return service.reconcile(new String(csv, StandardCharsets.UTF_8));
    }
}
