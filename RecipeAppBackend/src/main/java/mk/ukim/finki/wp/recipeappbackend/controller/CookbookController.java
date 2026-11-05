package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.model.createDTO.CookbookCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CookbookDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CookbookSummaryDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import mk.ukim.finki.wp.recipeappbackend.service.CookbookService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The caller's cookbooks. Identity comes from the JWT only, never a path or body value. */
@RestController
@RequestMapping("/api/users/me/cookbooks")
public class CookbookController {

    private final CookbookService cookbookService;

    public CookbookController(CookbookService cookbookService) {
        this.cookbookService = cookbookService;
    }

    @GetMapping
    public List<CookbookSummaryDto> list(@CurrentUserId UUID userId) {
        return cookbookService.list(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CookbookSummaryDto create(@CurrentUserId UUID userId, @RequestBody CookbookCreateDto body) {
        return cookbookService.create(userId, body);
    }

    @GetMapping("/{id}")
    public CookbookDto get(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return cookbookService.get(userId, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        cookbookService.delete(userId, id);
    }
}
