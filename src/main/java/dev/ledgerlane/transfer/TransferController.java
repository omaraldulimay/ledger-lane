package dev.ledgerlane.transfer;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/transfers")
class TransferController {

    private final TransferService service;

    TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<TransferResponse> transfer(@RequestHeader("Idempotency-Key") String key,
                                              @Valid @RequestBody TransferRequest request) {
        return respond(service.transfer(key, request));
    }

    @PostMapping("/{id}/reversal")
    ResponseEntity<TransferResponse> reverse(@RequestHeader("Idempotency-Key") String key, @PathVariable UUID id) {
        return respond(service.reverse(key, id));
    }

    /** 201 for a new entry, 200 with the original body when the key was seen before. */
    private static ResponseEntity<TransferResponse> respond(TransferService.Result result) {
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.transfer());
    }
}
