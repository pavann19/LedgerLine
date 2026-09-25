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
    public ResponseEntity<TransferResponse> transfer(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader(value = "X-Isolation-Variant", required = false) IsolationVariant requestedVariant,
        @Valid @RequestBody TransferRequest request
    ) {
        TransferResponse response = transferService.transfer(idempotencyKey, request, requestedVariant, currentPrincipal.id(), currentPrincipal.isOperator());
        HttpStatus status = response.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }
}
