package com.ledgerline.projection.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> forbidden(AccessDeniedException e,HttpServletRequest r){return problem(HttpStatus.FORBIDDEN,"forbidden","Forbidden",e.getMessage(),r);}
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalid(IllegalArgumentException e,HttpServletRequest r){return problem(HttpStatus.BAD_REQUEST,"invalid-request","Invalid request",e.getMessage(),r);}
    private ResponseEntity<ProblemDetail> problem(HttpStatus s,String type,String title,String detail,HttpServletRequest r){ProblemDetail p=ProblemDetail.forStatusAndDetail(s,detail);p.setType(URI.create("https://ledgerline.dev/problems/"+type));p.setTitle(title);p.setInstance(URI.create(r.getRequestURI()));return ResponseEntity.status(s).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);}
}
