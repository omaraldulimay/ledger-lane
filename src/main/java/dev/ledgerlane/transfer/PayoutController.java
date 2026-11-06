package dev.ledgerlane.transfer;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payouts")
class PayoutController {

    private final TransferService service;

    PayoutController(TransferService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<TransferResponse> payout(@RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody PayoutRequest request) {
        return TransferController.respond(service.payout(key, request));
    }
}
