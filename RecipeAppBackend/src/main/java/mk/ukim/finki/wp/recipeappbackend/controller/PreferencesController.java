package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.PreferencesDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import mk.ukim.finki.wp.recipeappbackend.service.PreferencesService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The signed-in user's food preferences. Always the JWT user's own row, never an id from the request. */
@RestController
@RequestMapping("/api/users/me/preferences")
public class PreferencesController {

    private final PreferencesService preferencesService;

    public PreferencesController(PreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    /** 404 here means the user has not answered the food questions yet. */
    @GetMapping
    public PreferencesDto get(@CurrentUserId UUID userId) {
        return preferencesService.get(userId);
    }

    @PutMapping
    public PreferencesDto save(@CurrentUserId UUID userId, @RequestBody PreferencesDto dto) {
        return preferencesService.save(userId, dto);
    }
}
