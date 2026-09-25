package com.ledgerline.ledger.api;

import com.ledgerline.ledger.exception.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(InsufficientFundsException.class)
    ResponseEntity<ProblemDetail> insufficient(InsufficientFundsException e, HttpServletRequest r) { return problem(HttpStatus.UNPROCESSABLE_ENTITY,"insufficient-funds","Insufficient funds",e.getMessage(),r,null); }
    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> idempotency(IdempotencyConflictException e, HttpServletRequest r) { return problem(HttpStatus.UNPROCESSABLE_ENTITY,"idempotency-conflict","Idempotency conflict",e.getMessage(),r,null); }
    @ExceptionHandler(AccountNotFoundException.class)
    ResponseEntity<ProblemDetail> missing(AccountNotFoundException e, HttpServletRequest r) { return problem(HttpStatus.NOT_FOUND,"account-not-found","Account not found",e.getMessage(),r,null); }
    @ExceptionHandler({InvalidTransferException.class, IllegalArgumentException.class})
    ResponseEntity<ProblemDetail> invalid(RuntimeException e, HttpServletRequest r) { return problem(HttpStatus.BAD_REQUEST,"invalid-request","Invalid request",e.getMessage(),r,null); }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> forbidden(AccessDeniedException e, HttpServletRequest r) { return problem(HttpStatus.FORBIDDEN,"forbidden","Forbidden",e.getMessage(),r,null); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e, HttpServletRequest r) {
        Map<String,String> errors=new LinkedHashMap<>(); for(FieldError f:e.getBindingResult().getFieldErrors()) errors.put(f.getField(),f.getDefaultMessage());
        return problem(HttpStatus.BAD_REQUEST,"validation-failed","Validation failed","Invalid request parameters",r,errors);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException e, HttpServletRequest r) { return problem(HttpStatus.CONFLICT,"data-integrity-violation","Data integrity violation","A database constraint rejected the request",r,null); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> general(Exception e, HttpServletRequest r) { return problem(HttpStatus.INTERNAL_SERVER_ERROR,"internal-error","Internal server error","The request could not be completed",r,null); }
    private ResponseEntity<ProblemDetail> problem(HttpStatus s,String type,String title,String detail,HttpServletRequest r,Map<String,String> errors){
        ProblemDetail p=ProblemDetail.forStatusAndDetail(s,detail); p.setType(URI.create("https://ledgerline.dev/problems/"+type)); p.setTitle(title); p.setInstance(URI.create(r.getRequestURI())); if(errors!=null)p.setProperty("errors",errors);
        return ResponseEntity.status(s).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }
}
