package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.food.FoodRules;
import mk.ukim.finki.wp.recipeappbackend.repository.ReferenceDataRepository;
import mk.ukim.finki.wp.recipeappbackend.security.MfaFactorLookup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FoodController.class)
@Import({SecurityConfig.class, FoodRules.class})
class FoodControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ReferenceDataRepository referenceDataRepository;
    @MockitoBean
    JwtDecoder jwtDecoder; // satisfies SecurityConfig's resource-server wiring
    @MockitoBean
    MfaFactorLookup mfaFactorLookup; // SecurityConfig's MFA filter; the default false keeps these tests unaffected

    @Test
    void anonymousGetsAllFourteenAllergens() throws Exception {
        mockMvc.perform(get("/api/food/allergens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(14))
                .andExpect(jsonPath("$[0].key").value("gluten"))
                .andExpect(jsonPath("$[0].label").value("Cereals containing gluten"));
    }

    @Test
    void suggestNormalisesTheQueryAndCapsAtTen() throws Exception {
        when(referenceDataRepository.suggestIngredients("gar", 10)).thenReturn(List.of("garlic", "garam masala"));

        mockMvc.perform(get("/api/ingredients/suggest").param("q", "Gar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("garlic"));

        verify(referenceDataRepository).suggestIngredients("gar", 10);
    }

    @Test
    void wildcardsAndTooShortQueriesReturnEmptyWithoutQuerying() throws Exception {
        for (String q : List.of("%", "_", "a", "")) {
            mockMvc.perform(get("/api/ingredients/suggest").param("q", q))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
        mockMvc.perform(get("/api/ingredients/suggest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verifyNoInteractions(referenceDataRepository);
    }
}
