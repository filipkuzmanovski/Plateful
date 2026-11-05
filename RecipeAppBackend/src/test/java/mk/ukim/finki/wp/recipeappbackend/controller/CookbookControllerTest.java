package mk.ukim.finki.wp.recipeappbackend.controller;

import mk.ukim.finki.wp.recipeappbackend.config.SecurityConfig;
import mk.ukim.finki.wp.recipeappbackend.exception.CookbookConflictException;
import mk.ukim.finki.wp.recipeappbackend.exception.InvalidCookbookException;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.CookbookCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CookbookDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.CookbookSummaryDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.SavedItemDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.SavedRecipeStubDto;
import mk.ukim.finki.wp.recipeappbackend.security.MfaFactorLookup;
import mk.ukim.finki.wp.recipeappbackend.service.CookbookService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CookbookController.class)
@Import(SecurityConfig.class)
class CookbookControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CookbookService cookbookService;
    @MockitoBean
    JwtDecoder jwtDecoder; // satisfies SecurityConfig's resource-server wiring
    @MockitoBean
    MfaFactorLookup mfaFactorLookup; // SecurityConfig's MFA filter; the default false keeps these tests unaffected

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOOK_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID RECIPE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String BODY = "{\"recipeIds\":[\"" + RECIPE_ID + "\"],\"subtitle\":\"Sub\"}";

    private static RequestPostProcessor authedAs(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("email", "jane@example.com"));
    }

    @Test
    void anonymousRequestsAre401() throws Exception {
        mockMvc.perform(get("/api/users/me/cookbooks")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/users/me/cookbooks").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me/cookbooks/{id}", BOOK_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/users/me/cookbooks/{id}", BOOK_ID)).andExpect(status().isUnauthorized());
        verifyNoInteractions(cookbookService);
    }

    @Test
    void list_returnsSummaries() throws Exception {
        when(cookbookService.list(USER_ID)).thenReturn(List.of(
                new CookbookSummaryDto(BOOK_ID, 2, null, OffsetDateTime.parse("2026-11-05T10:00:00Z"), 5)));

        mockMvc.perform(get("/api/users/me/cookbooks").with(authedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(BOOK_ID.toString()))
                .andExpect(jsonPath("$[0].volume").value(2))
                .andExpect(jsonPath("$[0].recipeCount").value(5));
    }

    @Test
    void create_is201WithBody_usingTheJwtUser() throws Exception {
        when(cookbookService.create(eq(USER_ID), any())).thenReturn(
                new CookbookSummaryDto(BOOK_ID, 1, "Sub", OffsetDateTime.parse("2026-11-05T10:00:00Z"), 5));

        mockMvc.perform(post("/api/users/me/cookbooks").with(authedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(BOOK_ID.toString()))
                .andExpect(jsonPath("$.subtitle").value("Sub"));

        ArgumentCaptor<CookbookCreateDto> body = ArgumentCaptor.forClass(CookbookCreateDto.class);
        verify(cookbookService).create(eq(USER_ID), body.capture());
        assertThat(body.getValue().recipeIds()).containsExactly(RECIPE_ID);
        assertThat(body.getValue().subtitle()).isEqualTo("Sub");
    }

    @Test
    void create_invalid_is400WithTheMessage() throws Exception {
        when(cookbookService.create(eq(USER_ID), any())).thenThrow(new InvalidCookbookException("Pick more."));

        mockMvc.perform(post("/api/users/me/cookbooks").with(authedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Pick more."));
    }

    @Test
    void create_conflict_is409WithTheMessage() throws Exception {
        when(cookbookService.create(eq(USER_ID), any()))
                .thenThrow(new CookbookConflictException("You can have up to 20 cookbooks."));

        mockMvc.perform(post("/api/users/me/cookbooks").with(authedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("You can have up to 20 cookbooks."));
    }

    @Test
    void get_returnsTheBook() throws Exception {
        SavedItemDto stub = SavedItemDto.ofStub(new SavedRecipeStubDto(RECIPE_ID, "Old", "unavailable"));
        when(cookbookService.get(USER_ID, BOOK_ID)).thenReturn(new CookbookDto(BOOK_ID, 1, null,
                OffsetDateTime.parse("2026-11-05T10:00:00Z"), "Ana", "Petrovska", List.of(stub)));

        mockMvc.perform(get("/api/users/me/cookbooks/{id}", BOOK_ID).with(authedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerFirstName").value("Ana"))
                .andExpect(jsonPath("$.ownerLastName").value("Petrovska"))
                .andExpect(jsonPath("$.chapters[0].stub.approvedTitle").value("Old"));
    }

    @Test
    void get_notTheCallers_is404() throws Exception {
        when(cookbookService.get(USER_ID, BOOK_ID)).thenThrow(new ResourceNotFoundException("Cookbook", BOOK_ID));

        mockMvc.perform(get("/api/users/me/cookbooks/{id}", BOOK_ID).with(authedAs(USER_ID)))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_is204() throws Exception {
        mockMvc.perform(delete("/api/users/me/cookbooks/{id}", BOOK_ID).with(authedAs(USER_ID)))
                .andExpect(status().isNoContent());

        verify(cookbookService).delete(USER_ID, BOOK_ID);
    }

    @Test
    void delete_notTheCallers_is404() throws Exception {
        doThrow(new ResourceNotFoundException("Cookbook", BOOK_ID)).when(cookbookService).delete(USER_ID, BOOK_ID);

        mockMvc.perform(delete("/api/users/me/cookbooks/{id}", BOOK_ID).with(authedAs(USER_ID)))
                .andExpect(status().isNotFound());
    }
}
