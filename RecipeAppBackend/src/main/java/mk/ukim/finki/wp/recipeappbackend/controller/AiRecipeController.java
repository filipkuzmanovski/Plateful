package mk.ukim.finki.wp.recipeappbackend.controller;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.exception.InvalidScanPhotoException;
import mk.ukim.finki.wp.recipeappbackend.generation.AiRecipeService;
import mk.ukim.finki.wp.recipeappbackend.generation.AiUsageService;
import mk.ukim.finki.wp.recipeappbackend.generation.PhotoScanService;
import mk.ukim.finki.wp.recipeappbackend.generation.PostImportService;
import mk.ukim.finki.wp.recipeappbackend.generation.RecipeGenerationService;
import mk.ukim.finki.wp.recipeappbackend.model.createDTO.GenerateRequestDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.AiUsageDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GenerateResultDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GeneratedRecipeDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GeneratedRecipeSummaryDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.ScanResultDto;
import mk.ukim.finki.wp.recipeappbackend.security.CurrentUserId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The caller's AI recipes. Identity comes from the JWT only. {@code {id}} is a UUID, so a request for
 * {@code /usage} (an exact path, which Spring prefers anyway) can never be read as an id.
 */
@RestController
@RequestMapping("/api/users/me/ai-recipes")
public class AiRecipeController {

    private final RecipeGenerationService generation;
    private final AiRecipeService recipes;
    private final AiUsageService usage;
    private final PhotoScanService scans;
    private final PostImportService postImports;

    public AiRecipeController(RecipeGenerationService generation, AiRecipeService recipes, AiUsageService usage,
                              PhotoScanService scans, PostImportService postImports) {
        this.generation = generation;
        this.recipes = recipes;
        this.usage = usage;
        this.scans = scans;
        this.postImports = postImports;
    }

    /** Can take up to a minute: the AI call happens inside. */
    @PostMapping("/generate")
    public GenerateResultDto generate(@CurrentUserId UUID userId, @Valid @RequestBody GenerateRequestDto body) {
        return generation.generate(userId, body);
    }

    /**
     * Reads the ingredients out of 1 to 3 kitchen photos (part {@code photos}, repeated). The photos are only
     * held in memory for this call. A missing part, or a field that is not a file, is the same 400 as a bad
     * photo, before anything is reserved.
     */
    @PostMapping(value = "/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ScanResultDto scan(@CurrentUserId UUID userId,
                              @RequestParam(name = "photos", required = false) List<MultipartFile> photos)
            throws IOException {
        if (photos == null || photos.isEmpty()) {
            throw new InvalidScanPhotoException("no photos in the request");
        }
        List<byte[]> bytes = new ArrayList<>(photos.size());
        for (MultipartFile photo : photos) {
            bytes.add(photo.getBytes());
        }
        return scans.scan(userId, bytes);
    }

    /**
     * Makes a private AI recipe from an Instagram post: the caption (part {@code caption}) and/or 1 to 2
     * screenshots (part {@code screenshots}, repeated). Either part may be missing, and a missing
     * {@code screenshots} part is an empty list; the service decides whether the request is enough. The
     * screenshots are only held in memory for this call. Can take up to a minute.
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GenerateResultDto importPost(@CurrentUserId UUID userId,
                                        @RequestParam(name = "caption", required = false) String caption,
                                        @RequestParam(name = "screenshots", required = false)
                                        List<MultipartFile> screenshots) throws IOException {
        List<byte[]> bytes = new ArrayList<>();
        if (screenshots != null) {
            for (MultipartFile screenshot : screenshots) {
                bytes.add(screenshot.getBytes());
            }
        }
        return postImports.importPost(userId, caption, bytes);
    }

    /** The saved ones, newest saved first. Size is capped by spring.data.web.pageable.max-page-size. */
    @GetMapping
    public Page<GeneratedRecipeSummaryDto> saved(@CurrentUserId UUID userId, Pageable pageable) {
        return recipes.saved(userId, pageable);
    }

    @GetMapping("/usage")
    public AiUsageDto usage(@CurrentUserId UUID userId) {
        return usage.usage(userId);
    }

    @GetMapping("/{id}")
    public GeneratedRecipeDto get(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return recipes.get(userId, id);
    }

    @PutMapping("/{id}/saved")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@CurrentUserId UUID userId, @PathVariable UUID id) {
        recipes.save(userId, id);
    }

    @DeleteMapping("/{id}/saved")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsave(@CurrentUserId UUID userId, @PathVariable UUID id) {
        recipes.unsave(userId, id);
    }
}
