package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CuisineInfoDto;
import mk.ukim.finki.wp.recipeappbackend.service.CuisineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CuisineController {

    private final CuisineService cuisineService;

    public CuisineController(CuisineService cuisineService) {
        this.cuisineService = cuisineService;
    }

    @GetMapping("/api/recipes/cuisines")
    public List<CuisineInfoDto> cuisines() {
        return cuisineService.listWithCounts();
    }
}
