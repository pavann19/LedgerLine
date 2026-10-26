package com.ledgerline.ledger.api;

import com.ledgerline.ledger.dto.AccountResponse;
import com.ledgerline.ledger.dto.CreateAccountRequest;
import com.ledgerline.ledger.dto.PostingDto;
import com.ledgerline.ledger.dto.CursorPage;
import com.ledgerline.ledger.security.CurrentPrincipal;
import com.ledgerline.ledger.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;
    private final CurrentPrincipal currentPrincipal;

    public AccountController(AccountService accountService, CurrentPrincipal currentPrincipal) {
        this.accountService = accountService;
        this.currentPrincipal = currentPrincipal;
    }

    @PostMapping
    @Operation(summary = "Create an account", description = "Customers can create CUSTOMER accounts. OPERATOR is required for INTERNAL or OVERDRAFT accounts.")
    @ApiResponse(responseCode = "201", description = "Account created")
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse response = accountService.createAccount(request, currentPrincipal.id(), currentPrincipal.isOperator());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Read an owned account")
    @ApiResponse(responseCode = "404", description = "Account not found")
    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.getAccount(id, currentPrincipal.id(), currentPrincipal.isOperator()));
    }

    @Operation(summary = "Read account postings", description = "Opaque cursor pagination; limit must be between 1 and 100.")
    @GetMapping("/{id}/postings")
    public ResponseEntity<CursorPage<PostingDto>> getPostings(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(required = false) String cursor
    ) {
        return ResponseEntity.ok(accountService.getPostings(id, limit, cursor, currentPrincipal.id(), currentPrincipal.isOperator()));
    }
}
