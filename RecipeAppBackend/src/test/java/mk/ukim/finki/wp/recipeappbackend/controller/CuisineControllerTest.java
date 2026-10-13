package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.cuisine.SeaCatalog;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CuisineInfoDto;
import mk.ukim.finki.wp.recipeappbackend.service.CuisineService;
import mk.ukim.finki.wp.recipeappbackend.service.RecipeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({CuisineController.class, RecipeController.class})
@Import({SecurityConfig.class, SeaCatalog.class})
class CuisineControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CuisineService cuisineService;
    @MockitoBean
    RecipeService recipeService;
    @MockitoBean
    JwtDecoder jwtDecoder;

    private void stubCatalogue() {
        when(cuisineService.listWithCounts()).thenReturn(List.of(
                new CuisineInfoDto("italian", "Italian", "europe", List.of("mediterranean"), 12)));
    }

    @Test
    void anonymousGetsCatalogue() throws Exception {
        stubCatalogue();

        mockMvc.perform(get("/api/recipes/cuisines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("italian"))
                .andExpect(jsonPath("$[0].label").value("Italian"))
                .andExpect(jsonPath("$[0].region").value("europe"))
                .andExpect(jsonPath("$[0].seas[0]").value("mediterranean"))
                .andExpect(jsonPath("$[0].count").value(12));
    }

    @Test
    void cuisinesPathIsNotTreatedAsRecipeId() throws Exception {
        stubCatalogue();

        mockMvc.perform(get("/api/recipes/cuisines")).andExpect(status().isOk());

        verifyNoInteractions(recipeService);
    }

    @Test
    void seasIsPublicAndReturnsFive() throws Exception {
        mockMvc.perform(get("/api/recipes/seas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].key").value("mediterranean"))
                .andExpect(jsonPath("$[3].label").value("Sea of Japan & Pacific"));
    }

    @Test
    void seasNotSwallowedByRecipeId() throws Exception {
        mockMvc.perform(get("/api/recipes/seas")).andExpect(status().isOk());

        verifyNoInteractions(recipeService);
    }

    @Test
    void cuisinesCarrySeas() throws Exception {
        stubCatalogue();

        mockMvc.perform(get("/api/recipes/cuisines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seas").isArray())
                .andExpect(jsonPath("$[0].mediterranean").doesNotExist());
    }
}
