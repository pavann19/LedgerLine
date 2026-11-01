package com.ledgerline.projection.api;

import com.ledgerline.projection.model.DailyAccountSummary;
import com.ledgerline.projection.model.StatementViewEntry;
import com.ledgerline.projection.service.ProjectionService;
import com.ledgerline.projection.model.CursorPage;
import com.ledgerline.projection.security.AccountClaimAuthorizer;
import org.springframework.security.core.Authentication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projections/accounts/{id}")
public class ProjectionController {

    private final ProjectionService projectionService;
    private final AccountClaimAuthorizer authorizer;

    public ProjectionController(ProjectionService projectionService, AccountClaimAuthorizer authorizer) {
        this.projectionService = projectionService;
        this.authorizer = authorizer;
    }

    @Operation(summary = "Read an account statement", description = "Eventually consistent projection. Customers need the account UUID in the signed accounts claim; operators bypass this claim check. Pagination is capped at 100.")
    @GetMapping("/statement")
    public ResponseEntity<CursorPage<StatementViewEntry>> getStatement(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(required = false) String cursor,
        Authentication authentication
    ) {
        authorizer.requireAccess(authentication, id);
        return ResponseEntity.ok(projectionService.getStatement(id, limit, cursor));
    }

    @Operation(summary = "Read a daily summary", description = "Date defaults to the current UTC date. Projection may lag committed transfers.")
    @ApiResponse(responseCode = "404", description = "No summary exists for the requested date")
    @GetMapping("/summary")
    public ResponseEntity<DailyAccountSummary> getDailySummary(
        @PathVariable UUID id,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        Authentication authentication
    ) {
        authorizer.requireAccess(authentication, id);
        LocalDate queryDate = date != null ? date : LocalDate.now(java.time.ZoneOffset.UTC);
        return projectionService.getDailySummary(id, queryDate)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
