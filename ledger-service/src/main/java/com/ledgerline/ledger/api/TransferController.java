package com.ledgerline.ledger.api;

import com.ledgerline.ledger.domain.IsolationVariant;
import com.ledgerline.ledger.dto.TransferRequest;
import com.ledgerline.ledger.dto.TransferResponse;
import com.ledgerline.ledger.service.TransferService;
import com.ledgerline.ledger.security.CurrentPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;
    private final CurrentPrincipal currentPrincipal;

    public TransferController(TransferService transferService, CurrentPrincipal currentPrincipal) {
        this.transferService = transferService;
        this.currentPrincipal = currentPrincipal;
    }

    @PostMapping
    @Operation(summary = "Create or replay a transfer", description = "Idempotency-Key is scoped to the authenticated subject. "
        + "Retry the identical request with the same key after transient errors. A changed payload for an existing key is rejected.")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Transfer durably committed"),
        @ApiResponse(responseCode = "200", description = "Original transfer returned by idempotent replay"),
        @ApiResponse(responseCode = "422", description = "Insufficient funds or idempotency-key payload conflict",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/problem+json",
                schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = org.springframework.http.ProblemDetail.class)))})
    public ResponseEntity<TransferResponse> transfer(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader(value = "X-Isolation-Variant", required = false) IsolationVariant requestedVariant,
        @Valid @RequestBody TransferRequest request
    ) {
        if (requestedVariant == IsolationVariant.VARIANT_0_BROKEN) {
            throw new IllegalArgumentException("The broken isolation variant is available only to internal experiments");
        }
        TransferResponse response = transferService.transfer(idempotencyKey, request, requestedVariant, currentPrincipal.id(), currentPrincipal.isOperator());
        HttpStatus status = response.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }
}
