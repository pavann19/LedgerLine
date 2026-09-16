package com.ledgerline.ledger.api;

import com.ledgerline.ledger.exception.AccountNotFoundException;
import com.ledgerline.ledger.exception.IdempotencyConflictException;
import com.ledgerline.ledger.exception.InsufficientFundsException;
import com.ledgerline.ledger.exception.InvalidTransferException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorResponse(
        String error,
        String message,
        int status,
        Instant timestamp,
        Map<String, String> details
    ) {}

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new ErrorResponse(
            "INSUFFICIENT_FUNDS",
            e.getMessage(),
            HttpStatus.UNPROCESSABLE_ENTITY.value(),
            Instant.now(),
            null
        ));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new ErrorResponse(
            "IDEMPOTENCY_CONFLICT",
            e.getMessage(),
            HttpStatus.UNPROCESSABLE_ENTITY.value(),
            Instant.now(),
            null
        ));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(
            "ACCOUNT_NOT_FOUND",
            e.getMessage(),
            HttpStatus.NOT_FOUND.value(),
            Instant.now(),
            null
        ));
    }

    @ExceptionHandler(InvalidTransferException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransfer(InvalidTransferException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(
            "INVALID_TRANSFER",
            e.getMessage(),
            HttpStatus.BAD_REQUEST.value(),
            Instant.now(),
            null
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(
            "VALIDATION_FAILED",
            "Invalid request parameters",
            HttpStatus.BAD_REQUEST.value(),
            Instant.now(),
            fieldErrors
        ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
            "DATA_INTEGRITY_VIOLATION",
            "Database constraint violated: " + (e.getRootCause() != null ? e.getRootCause().getMessage() : e.getMessage()),
            HttpStatus.CONFLICT.value(),
            Instant.now(),
            null
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
            "INTERNAL_SERVER_ERROR",
            e.getMessage(),
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            Instant.now(),
            null
        ));
    }
}
