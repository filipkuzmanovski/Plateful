package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.cuisine.Sea;
import mk.ukim.finki.wp.recipeappbackend.cuisine.SeaCatalog;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CuisineInfoDto;
import mk.ukim.finki.wp.recipeappbackend.service.CuisineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CuisineController {

    private final CuisineService cuisineService;

    private final SeaCatalog seaCatalog;

    public CuisineController(CuisineService cuisineService, SeaCatalog seaCatalog) {
        this.cuisineService = cuisineService;
        this.seaCatalog = seaCatalog;
    }

    @GetMapping("/api/recipes/cuisines")
    public List<CuisineInfoDto> cuisines() {
        return cuisineService.listWithCounts();
    }

    @GetMapping("/api/recipes/seas")
    public List<Sea> seas() {
        return seaCatalog.all();
    }
}
