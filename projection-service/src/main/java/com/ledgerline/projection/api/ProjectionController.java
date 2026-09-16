package com.ledgerline.projection.api;

import com.ledgerline.projection.model.DailyAccountSummary;
import com.ledgerline.projection.model.StatementViewEntry;
import com.ledgerline.projection.service.ProjectionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/projections/accounts/{id}")
public class ProjectionController {

    private final ProjectionService projectionService;

    public ProjectionController(ProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @GetMapping("/statement")
    public ResponseEntity<List<StatementViewEntry>> getStatement(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return ResponseEntity.ok(projectionService.getStatement(id, limit, offset));
    }

    @GetMapping("/summary")
    public ResponseEntity<DailyAccountSummary> getDailySummary(
        @PathVariable UUID id,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        LocalDate queryDate = date != null ? date : LocalDate.now();
        return projectionService.getDailySummary(id, queryDate)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
