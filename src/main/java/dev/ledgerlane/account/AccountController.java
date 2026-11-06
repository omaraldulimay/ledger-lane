package dev.ledgerlane.account;

import dev.ledgerlane.api.LedgerException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounts")
class AccountController {

    private static final int MAX_PAGE = 200;

    private final AccountRepository accounts;

    AccountController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        boolean created = accounts.insert(request.id(), request.type(), request.currency(),
                Boolean.TRUE.equals(request.allowNegative()));
        if (!created) {
            throw LedgerException.conflict("Account exists", "Account " + request.id() + " already exists");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(load(request.id())));
    }

    @GetMapping("/{id}")
    AccountResponse get(@PathVariable String id) {
        return AccountResponse.from(load(id));
    }

    @GetMapping("/{id}/postings")
    PostingPage postings(@PathVariable String id,
                         @RequestParam(required = false) String cursor,
                         @RequestParam(defaultValue = "50") int limit) {
        load(id);
        Long after;
        try {
            after = cursor == null ? null : Long.valueOf(cursor);
        } catch (NumberFormatException e) {
            throw LedgerException.invalid("Malformed cursor");
        }
        return accounts.postings(id, after, Math.max(1, Math.min(limit, MAX_PAGE)));
    }

    private Account load(String id) {
        return accounts.find(id).orElseThrow(() -> LedgerException.notFound("Account " + id + " does not exist"));
    }
}
