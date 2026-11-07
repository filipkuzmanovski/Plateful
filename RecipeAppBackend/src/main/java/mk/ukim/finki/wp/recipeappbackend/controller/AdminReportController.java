package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.ReportedItemDto;
import mk.ukim.finki.wp.recipeappbackend.service.AdminReportService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** The admin reports queue. AdminGuardInterceptor (see WebConfig) guards every route under /api/admin. */
@RestController
@RequestMapping("/api/admin/reports")
public class AdminReportController {

    private final AdminReportService reportService;

    public AdminReportController(AdminReportService reportService) {
        this.reportService = reportService;
    }

    /** Size is capped by spring.data.web.pageable.max-page-size; any sort is ignored. */
    @GetMapping
    public Page<ReportedItemDto> queue(Pageable pageable) {
        return reportService.queue(pageable);
    }

    @GetMapping("/count")
    public Map<String, Long> count() {
        return Map.of("count", reportService.count());
    }

    @PostMapping("/recipes/{id}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismissRecipe(@PathVariable UUID id) {
        reportService.dismissRecipe(id);
    }

    @PostMapping("/comments/{id}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismissComment(@PathVariable UUID id) {
        reportService.dismissComment(id);
    }
}
