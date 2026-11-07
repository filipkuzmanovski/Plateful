package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.model.createDTO.ReportCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.MyReportsDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import mk.ukim.finki.wp.recipeappbackend.service.ReportService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Signed-in people report recipes and comments (spec 3.3). Everything under /api/reports needs a signed-in
 * caller (SecurityConfig), and the MFA filter guards the POST like every other write. The reporter comes from
 * the JWT only, never the body.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@CurrentUserId UUID reporterId, @RequestBody ReportCreateDto body) {
        reportService.report(reporterId, body);
    }

    /**
     * Which of the given items the caller has reported, so the page can show "Reported". {@code commentIds}
     * may be repeated or comma-separated, at most 100.
     */
    @GetMapping("/mine")
    public MyReportsDto mine(@CurrentUserId UUID reporterId,
                             @RequestParam(required = false) UUID recipeId,
                             @RequestParam(required = false) List<UUID> commentIds) {
        return reportService.mine(reporterId, recipeId, commentIds);
    }
}
