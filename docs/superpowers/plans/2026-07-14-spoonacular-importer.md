# Spoonacular Importer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A property-gated import job that pulls ~400 recipes from Spoonacular's `complexSearch` API (10 cuisines × 40, configurable), translates them into the existing `Recipe`/`RecipeIngredient` entities, dedups on `spoonacular_id`, and saves them to the DB.

**Architecture:** New package `mk.ukim.finki.wp.recipeappbackend.spoonacular`: `SpoonacularClient` (RestClient wrapper) → `SpoonacularRecipeTranslator` (pure mapping) → `SpoonacularImportService` (orchestration, dedup, per-recipe isolation via a small `SpoonacularRecipePersister` with one transaction per recipe) → `SpoonacularImportRunner` (`ApplicationRunner`, active only when `spoonacular.import.enabled=true`). No new endpoints, no schema changes.

**Tech Stack:** Spring Boot 4.1.0, Java 21, `RestClient`, Jackson 3 (`tools.jackson.*`), JUnit 5 + Mockito + `MockRestServiceServer`.

**Spec:** `docs/superpowers/specs/2026-07-14-spoonacular-importer-design.md`

## Global Constraints

- Base package `mk.ukim.finki.wp.recipeappbackend`; new code under `...recipeappbackend.spoonacular`. Source root `RecipeAppBackend/src/main/java/...`, test root `RecipeAppBackend/src/test/java/...`.
- Run ALL commands from `RecipeAppBackend`. **Set JAVA_HOME first** — the PATH java is 1.8: PowerShell `$env:JAVA_HOME = "$env:USERPROFILE\.jdks\ms-21.0.9"`, bash `export JAVA_HOME="$USERPROFILE/.jdks/ms-21.0.9"`.
- **Never run the unfiltered test suite** (`RecipeAppBackendApplicationTests` needs the Supabase DB). Always `.\mvnw.cmd test "-Dtest=ClassName"`.
- API key: property `spoonacular.api-key=${SPOONACULAR_API_KEY:}` — empty default so the app boots without the key; the runner fail-fasts only when an import is actually enabled. Never hardcode any key value anywhere.
- API request shape (exact): `GET {baseUrl}/recipes/complexSearch?cuisine={c}&number={n}&offset={o}&addRecipeInformation=true&addRecipeNutrition=true&fillIngredients=true&instructionsRequired=true&sort=popularity`, key in `x-api-key` header (never the URL).
- Default config values: baseUrl `https://api.spoonacular.com`, cuisines `italian,mexican,chinese,indian,greek,french,japanese,thai,spanish,american`, recipes-per-cuisine `40`, offset `0`, enabled `false`.
- Imported rows: `source="spoonacular"`, `user=null`, `spoonacularId` set; `title`/`sourceName` truncated to 255 chars; cuisines lowercased with fallback to the searched cuisine when the API returns none.
- HTTP 402 → `QuotaExceededException` → stop fetching, keep saved work, report progress.
- Jackson 3 note: package is `tools.jackson.databind` (annotations stay `com.fasterxml.jackson.annotation`). Boot 4 test annotations live under `org.springframework.boot.<module>.test.autoconfigure` (e.g. `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` — already used in this repo).
- Commit after every task; commit subjects exactly as given; no co-author lines.

---

### Task 1: Foundation — properties, response records, exception, repository method, config entries

**Files:**
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularProperties.java`
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularImportProperties.java`
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularSearchResponse.java`
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/QuotaExceededException.java`
- Modify: `src/main/java/mk/ukim/finki/wp/recipeappbackend/RecipeAppBackendApplication.java` (add `@ConfigurationPropertiesScan`)
- Modify: `src/main/java/mk/ukim/finki/wp/recipeappbackend/repository/RecipeRepository.java` (add `existsBySpoonacularId`)
- Modify: `src/main/resources/application.properties` (append spoonacular block)
- Test: `src/test/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularPropertiesTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces (used by Tasks 2–5):
  - `SpoonacularProperties(String apiKey, String baseUrl)` — prefix `spoonacular`
  - `SpoonacularImportProperties(boolean enabled, List<String> cuisines, int recipesPerCuisine, int offset)` — prefix `spoonacular.import` (two records because `import` is a Java keyword and can't be a nested component name)
  - `SpoonacularSearchResponse(List<Result> results)` with nested records `Result`, `AnalyzedInstruction`, `Step`, `NamedItem`, `Nutrition`, `Nutrient`, `ExtendedIngredient` (exact shapes below)
  - `QuotaExceededException extends RuntimeException`
  - `RecipeRepository.existsBySpoonacularId(Long spoonacularId)` → `boolean`

- [ ] **Step 1: Write the failing binding test**

Create `src/test/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularPropertiesTest.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpoonacularPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @EnableConfigurationProperties({SpoonacularProperties.class, SpoonacularImportProperties.class})
    static class TestConfig {
    }

    @Test
    void bindsAllProperties() {
        runner.withPropertyValues(
                "spoonacular.api-key=test-key",
                "spoonacular.base-url=http://localhost:9999",
                "spoonacular.import.enabled=true",
                "spoonacular.import.cuisines=italian,thai",
                "spoonacular.import.recipes-per-cuisine=5",
                "spoonacular.import.offset=10"
        ).run(ctx -> {
            SpoonacularProperties props = ctx.getBean(SpoonacularProperties.class);
            SpoonacularImportProperties imp = ctx.getBean(SpoonacularImportProperties.class);
            assertThat(props.apiKey()).isEqualTo("test-key");
            assertThat(props.baseUrl()).isEqualTo("http://localhost:9999");
            assertThat(imp.enabled()).isTrue();
            assertThat(imp.cuisines()).containsExactly("italian", "thai");
            assertThat(imp.recipesPerCuisine()).isEqualTo(5);
            assertThat(imp.offset()).isEqualTo(10);
        });
    }

    @Test
    void importDisabledByDefaultAndBaseUrlDefaults() {
        runner.run(ctx -> {
            assertThat(ctx.getBean(SpoonacularImportProperties.class).enabled()).isFalse();
            assertThat(ctx.getBean(SpoonacularProperties.class).baseUrl())
                    .isEqualTo("https://api.spoonacular.com");
        });
    }
}
```

Note: if `org.springframework.boot.test.context.runner.ApplicationContextRunner` doesn't resolve under Boot 4, search the local repo for `ApplicationContextRunner` (`~/.m2`) and use its actual package; note the change in your report.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=SpoonacularPropertiesTest"`
Expected: BUILD FAILURE — `cannot find symbol: class SpoonacularProperties`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularProperties.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Top-level Spoonacular settings. The API key arrives via the
 * SPOONACULAR_API_KEY environment variable (see application.properties) and
 * may legitimately be EMPTY — the app must boot without it. The import
 * runner is the only place that requires it, and validates just-in-time.
 */
@ConfigurationProperties(prefix = "spoonacular")
public record SpoonacularProperties(
        String apiKey,
        @DefaultValue("https://api.spoonacular.com") String baseUrl
) {
}
```

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularImportProperties.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Import-run settings. Separate record (not nested in SpoonacularProperties)
 * because the property prefix segment "import" is a Java keyword and cannot
 * be a record component name.
 */
@ConfigurationProperties(prefix = "spoonacular.import")
public record SpoonacularImportProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue({"italian", "mexican", "chinese", "indian", "greek",
                "french", "japanese", "thai", "spanish", "american"}) List<String> cuisines,
        @DefaultValue("40") int recipesPerCuisine,
        @DefaultValue("0") int offset
) {
}
```

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/QuotaExceededException.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

/**
 * Spoonacular returned HTTP 402: the daily points quota is used up.
 * Signals the import loop to stop fetching but KEEP everything already
 * saved — a re-run after the quota resets continues safely (dedup skips).
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException() {
        super("Spoonacular daily quota exhausted (HTTP 402)");
    }
}
```

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularSearchResponse.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * The slice of Spoonacular's complexSearch response we consume (with
 * addRecipeInformation, addRecipeNutrition and fillIngredients enabled).
 * Boxed Booleans on purpose: absent flags must map to false in the
 * translator, never depend on mapper leniency for primitives.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpoonacularSearchResponse(List<Result> results) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            long id,
            String title,
            String image,
            Integer servings,
            Integer readyInMinutes,
            Integer cookingMinutes,
            Integer preparationMinutes,
            String sourceName,
            String sourceUrl,
            String instructions,
            List<AnalyzedInstruction> analyzedInstructions,
            Boolean vegan,
            Boolean vegetarian,
            Boolean glutenFree,
            Boolean dairyFree,
            List<String> cuisines,
            Nutrition nutrition,
            List<ExtendedIngredient> extendedIngredients
    ) {
    }

    /** One instruction GROUP (e.g. "For the sauce") — steps renumber per group in the raw API. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnalyzedInstruction(String name, List<Step> steps) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(Integer number, String step, List<NamedItem> ingredients, List<NamedItem> equipment) {
    }

    /** Raw API items are objects ({id, name, image, ...}); we keep names only. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NamedItem(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Nutrition(List<Nutrient> nutrients) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Nutrient(String name, BigDecimal amount, String unit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtendedIngredient(String name, String nameClean, String original, String originalName) {
    }
}
```

Modify `src/main/java/mk/ukim/finki/wp/recipeappbackend/RecipeAppBackendApplication.java` — add the annotation and import:

```java
package mk.ukim.finki.wp.recipeappbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RecipeAppBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecipeAppBackendApplication.class, args);
    }

}
```

Modify `src/main/java/mk/ukim/finki/wp/recipeappbackend/repository/RecipeRepository.java` — add one method to the interface body (keep everything else untouched):

```java
    boolean existsBySpoonacularId(Long spoonacularId);
```

Append to `src/main/resources/application.properties`:

```properties

# --- Spoonacular import (see docs/superpowers/specs/2026-07-14-spoonacular-importer-design.md) ---
# Key comes from the environment (IntelliJ run config), empty default so the
# app boots fine without it; the import runner validates it just-in-time.
spoonacular.api-key=${SPOONACULAR_API_KEY:}
spoonacular.base-url=https://api.spoonacular.com
# Import runs ONLY when this is true (pass --spoonacular.import.enabled=true).
spoonacular.import.enabled=false
spoonacular.import.cuisines=italian,mexican,chinese,indian,greek,french,japanese,thai,spanish,american
spoonacular.import.recipes-per-cuisine=40
spoonacular.import.offset=0
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=SpoonacularPropertiesTest"`
Expected: `Tests run: 2, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular src/main/java/mk/ukim/finki/wp/recipeappbackend/RecipeAppBackendApplication.java src/main/java/mk/ukim/finki/wp/recipeappbackend/repository/RecipeRepository.java src/main/resources/application.properties src/test/java/mk/ukim/finki/wp/recipeappbackend/spoonacular
git commit -m "feat: spoonacular importer foundation (properties, response records, dedup query)"
```

---

### Task 2: Translator + JSON fixture

**Files:**
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularRecipeTranslator.java`
- Create: `src/test/resources/spoonacular/complex-search-response.json`
- Test: `src/test/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularRecipeTranslatorTest.java`

**Interfaces:**
- Consumes: `SpoonacularSearchResponse.Result` and nested records (Task 1); existing `Recipe`, `RecipeIngredient`, `InstructionStep`.
- Produces: `SpoonacularRecipeTranslator` with method `TranslationResult translate(SpoonacularSearchResponse.Result result, String searchedCuisine)` and nested record `TranslationResult(Recipe recipe, List<RecipeIngredient> ingredients)`. Tasks 4–5 rely on exactly these names. The fixture file is reused by Task 3's client test.

- [ ] **Step 1: Create the fixture**

Create `src/test/resources/spoonacular/complex-search-response.json` (recipe 1 = fully populated with two instruction groups; recipe 2 = degenerate: empty cuisines, no nutrition, no analyzedInstructions, sparse ingredients, over-long title):

```json
{
  "offset": 0,
  "number": 2,
  "totalResults": 5234,
  "results": [
    {
      "id": 715538,
      "title": "Bruschetta Style Pork & Pasta",
      "image": "https://img.spoonacular.com/recipes/715538-312x231.jpg",
      "imageType": "jpg",
      "servings": 5,
      "readyInMinutes": 35,
      "cookingMinutes": 25,
      "preparationMinutes": 10,
      "sourceName": "Pink When",
      "sourceUrl": "https://www.pinkwhen.com/bruschetta-style-pork-pasta/",
      "instructions": "Cook the pasta. Sear the pork. Combine and serve.",
      "vegan": false,
      "vegetarian": false,
      "glutenFree": true,
      "dairyFree": true,
      "cheap": false,
      "cuisines": ["Mediterranean", "Italian"],
      "analyzedInstructions": [
        {
          "name": "",
          "steps": [
            {
              "number": 1,
              "step": "Bring a pot of salted water to a boil and cook the pasta.",
              "ingredients": [{"id": 20420, "name": "pasta", "image": "fusilli.jpg"}],
              "equipment": [{"id": 404752, "name": "pot", "image": "stock-pot.jpg"}]
            },
            {
              "number": 2,
              "step": "Season the pork chops and sear until golden.",
              "ingredients": [{"id": 10218, "name": "pork chops", "image": "pork-chops.jpg"}],
              "equipment": [{"id": 404645, "name": "frying pan", "image": "pan.png", "temperature": {"number": 200.0, "unit": "Fahrenheit"}}]
            }
          ]
        },
        {
          "name": "For the bruschetta topping",
          "steps": [
            {
              "number": 1,
              "step": "Dice the tomatoes and mix with basil and garlic.",
              "ingredients": [
                {"id": 11529, "name": "tomato", "image": "tomato.png"},
                {"id": 2044, "name": "basil", "image": "basil.jpg"}
              ],
              "equipment": []
            },
            {
              "number": 2,
              "step": "Toss everything together and serve immediately.",
              "ingredients": [],
              "equipment": []
            }
          ]
        }
      ],
      "nutrition": {
        "nutrients": [
          {"name": "Calories", "amount": 543.36, "unit": "kcal", "percentOfDailyNeeds": 27.17},
          {"name": "Protein", "amount": 21.1, "unit": "g", "percentOfDailyNeeds": 42.2},
          {"name": "Fat", "amount": 16.2, "unit": "g", "percentOfDailyNeeds": 24.92},
          {"name": "Carbohydrates", "amount": 74.79, "unit": "g", "percentOfDailyNeeds": 24.93},
          {"name": "Sodium", "amount": 341.94, "unit": "mg", "percentOfDailyNeeds": 14.87}
        ]
      },
      "extendedIngredients": [
        {
          "id": 20420,
          "aisle": "Pasta and Rice",
          "name": "penne pasta",
          "nameClean": "penne",
          "original": "8 oz penne pasta, cooked",
          "originalName": "penne pasta, cooked"
        },
        {
          "id": 10218,
          "aisle": "Meat",
          "name": "pork chops",
          "nameClean": null,
          "original": "2 boneless pork chops, cubed",
          "originalName": "boneless pork chops, cubed"
        }
      ]
    },
    {
      "id": 999001,
      "title": "An Extremely Long Recipe Title That Goes On And On Far Beyond Any Reasonable Length Because Some Recipe Sites Stuff Keywords Into Their Titles For Search Engine Optimization Purposes And The Spoonacular API Passes Those Titles Through Verbatim Without Truncating Them At All Which Would Overflow Our Varchar Column",
      "image": null,
      "servings": 2,
      "readyInMinutes": 15,
      "sourceName": null,
      "sourceUrl": "https://example.com/mystery",
      "instructions": "Mix and eat.",
      "vegan": true,
      "vegetarian": true,
      "glutenFree": false,
      "cuisines": [],
      "extendedIngredients": [
        {
          "id": 11165,
          "name": "cilantro",
          "nameClean": null,
          "original": null,
          "originalName": null
        },
        {
          "id": 0,
          "name": null,
          "nameClean": null,
          "original": null,
          "originalName": null
        }
      ]
    }
  ]
}
```

(Recipe 2 deliberately omits `cookingMinutes`, `preparationMinutes`, `dairyFree`, `analyzedInstructions`, and `nutrition` — those come back as null and must be tolerated. Its title is 300+ characters.)

- [ ] **Step 2: Write the failing test**

Create `src/test/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularRecipeTranslatorTest.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import mk.ukim.finki.wp.recipeappbackend.model.InstructionStep;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpoonacularRecipeTranslatorTest {

    private static SpoonacularSearchResponse.Result full;      // recipe 715538
    private static SpoonacularSearchResponse.Result sparse;    // recipe 999001

    private final SpoonacularRecipeTranslator translator = new SpoonacularRecipeTranslator();

    @BeforeAll
    static void loadFixture() throws Exception {
        try (InputStream in = SpoonacularRecipeTranslatorTest.class
                .getResourceAsStream("/spoonacular/complex-search-response.json")) {
            SpoonacularSearchResponse response = JsonMapper.builder().build()
                    .readValue(in, SpoonacularSearchResponse.class);
            full = response.results().get(0);
            sparse = response.results().get(1);
        }
    }

    @Test
    void mapsCoreFieldsAndProvenance() {
        Recipe r = translator.translate(full, "italian").recipe();

        assertThat(r.getSource()).isEqualTo("spoonacular");
        assertThat(r.getUser()).isNull();
        assertThat(r.getSpoonacularId()).isEqualTo(715538L);
        assertThat(r.getTitle()).isEqualTo("Bruschetta Style Pork & Pasta");
        assertThat(r.getServings()).isEqualTo(5);
        assertThat(r.getReadyInMinutes()).isEqualTo(35);
        assertThat(r.getSourceName()).isEqualTo("Pink When");
        assertThat(r.getInstructions()).contains("Cook the pasta");
    }

    @Test
    void flattensInstructionGroupsWithContinuousNumbering() {
        List<InstructionStep> steps = translator.translate(full, "italian").recipe().getInstructionsSteps();

        assertThat(steps).hasSize(4);
        assertThat(steps).extracting(InstructionStep::number).containsExactly(1, 2, 3, 4);
        // group 2's first step (raw number 1) became step 3
        assertThat(steps.get(2).step()).contains("Dice the tomatoes");
        // names only — objects reduced to their name field
        assertThat(steps.get(0).ingredients()).containsExactly("pasta");
        assertThat(steps.get(1).equipment()).containsExactly("frying pan");
        assertThat(steps.get(3).ingredients()).isEmpty();
    }

    @Test
    void extractsMacrosAndKeepsFullNutrientListInJsonbMap() {
        Recipe r = translator.translate(full, "italian").recipe();

        assertThat(r.getCalories()).isEqualByComparingTo(new BigDecimal("543.36"));
        assertThat(r.getProteinGrams()).isEqualByComparingTo(new BigDecimal("21.1"));
        assertThat(r.getFatGrams()).isEqualByComparingTo(new BigDecimal("16.2"));
        assertThat(r.getCarbsGrams()).isEqualByComparingTo(new BigDecimal("74.79"));
        assertThat(r.getNutrition()).containsKey("nutrients");
        assertThat((List<?>) r.getNutrition().get("nutrients")).hasSize(5); // Sodium kept too
    }

    @Test
    void lowercasesCuisines() {
        Recipe r = translator.translate(full, "italian").recipe();
        assertThat(r.getCuisines()).containsExactly("mediterranean", "italian");
    }

    @Test
    void emptyCuisinesFallBackToSearchedCuisine() {
        Recipe r = translator.translate(sparse, "Thai").recipe();
        assertThat(r.getCuisines()).containsExactly("thai");
    }

    @Test
    void mapsIngredientsWithFallbacksAndDropsEmptyOnes() {
        List<RecipeIngredient> ingredients = translator.translate(full, "italian").ingredients();

        assertThat(ingredients).hasSize(2);
        assertThat(ingredients.get(0).getIngredientName()).isEqualTo("penne");            // nameClean wins
        assertThat(ingredients.get(0).getOriginalText()).isEqualTo("8 oz penne pasta, cooked");
        assertThat(ingredients.get(0).getSortOrder()).isEqualTo(0);
        assertThat(ingredients.get(1).getIngredientName()).isEqualTo("pork chops");       // nameClean null -> name
        assertThat(ingredients.get(1).getSortOrder()).isEqualTo(1);

        List<RecipeIngredient> sparseIngredients = translator.translate(sparse, "thai").ingredients();
        assertThat(sparseIngredients).hasSize(1); // fully-empty ingredient dropped
        assertThat(sparseIngredients.get(0).getIngredientName()).isEqualTo("cilantro");
        assertThat(sparseIngredients.get(0).getOriginalText()).isEqualTo("cilantro");     // text falls back to name
    }

    @Test
    void toleratesMissingOptionalSections() {
        Recipe r = translator.translate(sparse, "thai").recipe();

        assertThat(r.getInstructionsSteps()).isEmpty();  // no analyzedInstructions
        assertThat(r.getNutrition()).isNull();           // no nutrition block
        assertThat(r.getCalories()).isNull();
        assertThat(r.isVegan()).isTrue();
        assertThat(r.isDairyFree()).isFalse();           // absent flag -> false
        assertThat(r.getCookingMinutes()).isNull();
    }

    @Test
    void truncatesOverlongTitleTo255() {
        Recipe r = translator.translate(sparse, "thai").recipe();
        assertThat(r.getTitle()).hasSize(255);
        assertThat(r.getTitle()).startsWith("An Extremely Long Recipe Title");
    }

    @Test
    void ingredientsReferenceTheTranslatedRecipe() {
        SpoonacularRecipeTranslator.TranslationResult result = translator.translate(full, "italian");
        assertThat(result.ingredients()).allSatisfy(i -> assertThat(i.getRecipe()).isSameAs(result.recipe()));
    }
}
```

Note: if `tools.jackson.databind.json.JsonMapper` doesn't resolve, use `tools.jackson.databind.ObjectMapper` (`new ObjectMapper()`); note it in your report.

- [ ] **Step 3: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=SpoonacularRecipeTranslatorTest"`
Expected: BUILD FAILURE — `cannot find symbol: class SpoonacularRecipeTranslator`.

- [ ] **Step 4: Write the implementation**

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularRecipeTranslator.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import mk.ukim.finki.wp.recipeappbackend.model.InstructionStep;
import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure mapping from one Spoonacular complexSearch result to our entities.
 * No I/O, no Spring dependencies — everything testable against a JSON
 * fixture. All the shape-translation rules documented on InstructionStep
 * (flatten groups, renumber continuously, keep names only) live here.
 */
public class SpoonacularRecipeTranslator {

    public record TranslationResult(Recipe recipe, List<RecipeIngredient> ingredients) {
    }

    public TranslationResult translate(SpoonacularSearchResponse.Result result, String searchedCuisine) {
        Recipe recipe = Recipe.builder()
                .source("spoonacular")
                .user(null)
                .spoonacularId(result.id())
                .title(truncate(result.title(), 255))
                .image(result.image())
                .servings(result.servings())
                .readyInMinutes(result.readyInMinutes())
                .cookingMinutes(result.cookingMinutes())
                .preparationMinutes(result.preparationMinutes())
                .sourceName(truncate(result.sourceName(), 255))
                .sourceUrl(result.sourceUrl())
                .instructions(result.instructions())
                .instructionsSteps(flattenInstructions(result.analyzedInstructions()))
                .vegan(Boolean.TRUE.equals(result.vegan()))
                .vegetarian(Boolean.TRUE.equals(result.vegetarian()))
                .glutenFree(Boolean.TRUE.equals(result.glutenFree()))
                .dairyFree(Boolean.TRUE.equals(result.dairyFree()))
                .cuisines(mapCuisines(result.cuisines(), searchedCuisine))
                .calories(findNutrient(result.nutrition(), "Calories"))
                .proteinGrams(findNutrient(result.nutrition(), "Protein"))
                .fatGrams(findNutrient(result.nutrition(), "Fat"))
                .carbsGrams(findNutrient(result.nutrition(), "Carbohydrates"))
                .nutrition(compactNutrition(result.nutrition()))
                .build();

        return new TranslationResult(recipe, mapIngredients(result.extendedIngredients(), recipe));
    }

    /** Flatten all groups into one list, renumbered 1..N (raw numbers restart per group). */
    private List<InstructionStep> flattenInstructions(List<SpoonacularSearchResponse.AnalyzedInstruction> groups) {
        if (groups == null) {
            return List.of();
        }
        List<InstructionStep> steps = new ArrayList<>();
        int number = 1;
        for (SpoonacularSearchResponse.AnalyzedInstruction group : groups) {
            if (group.steps() == null) {
                continue;
            }
            for (SpoonacularSearchResponse.Step step : group.steps()) {
                if (step.step() == null || step.step().isBlank()) {
                    continue;
                }
                steps.add(new InstructionStep(number++, step.step(),
                        names(step.ingredients()), names(step.equipment())));
            }
        }
        return steps;
    }

    /** Raw items are objects ({id, name, image, ...}); keep the names only. */
    private List<String> names(List<SpoonacularSearchResponse.NamedItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .map(SpoonacularSearchResponse.NamedItem::name)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    /** Lowercase; fall back to the searched cuisine so every import stays filterable. */
    private List<String> mapCuisines(List<String> cuisines, String searchedCuisine) {
        if (cuisines == null || cuisines.isEmpty()) {
            return List.of(searchedCuisine.toLowerCase(Locale.ROOT));
        }
        return cuisines.stream().map(c -> c.toLowerCase(Locale.ROOT)).toList();
    }

    private java.math.BigDecimal findNutrient(SpoonacularSearchResponse.Nutrition nutrition, String name) {
        if (nutrition == null || nutrition.nutrients() == null) {
            return null;
        }
        return nutrition.nutrients().stream()
                .filter(n -> name.equalsIgnoreCase(n.name()))
                .map(SpoonacularSearchResponse.Nutrient::amount)
                .findFirst()
                .orElse(null);
    }

    /** Full nutrient list, reduced to {name, amount, unit}, for the display-only jsonb column. */
    private Map<String, Object> compactNutrition(SpoonacularSearchResponse.Nutrition nutrition) {
        if (nutrition == null || nutrition.nutrients() == null) {
            return null;
        }
        List<Map<String, Object>> nutrients = nutrition.nutrients().stream()
                .map(n -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("name", n.name());
                    m.put("amount", n.amount());
                    m.put("unit", n.unit());
                    return (Map<String, Object>) m;
                })
                .toList();
        return Map.of("nutrients", nutrients);
    }

    private List<RecipeIngredient> mapIngredients(List<SpoonacularSearchResponse.ExtendedIngredient> raw,
                                                  Recipe recipe) {
        if (raw == null) {
            return List.of();
        }
        List<RecipeIngredient> ingredients = new ArrayList<>();
        int sortOrder = 0;
        for (SpoonacularSearchResponse.ExtendedIngredient ingredient : raw) {
            String name = firstNonBlank(ingredient.nameClean(), ingredient.name());
            String text = firstNonBlank(ingredient.original(), ingredient.originalName(), ingredient.name());
            if (name == null || text == null) {
                continue; // nothing usable — drop rather than violate NOT NULL
            }
            ingredients.add(RecipeIngredient.builder()
                    .recipe(recipe)
                    .ingredientName(name)
                    .originalText(text)
                    .sortOrder(sortOrder++)
                    .build());
        }
        return ingredients;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=SpoonacularRecipeTranslatorTest"`
Expected: `Tests run: 9, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularRecipeTranslator.java src/test/resources/spoonacular src/test/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularRecipeTranslatorTest.java
git commit -m "feat: spoonacular-to-entity translator with fixture tests"
```

---

### Task 3: Client + bean wiring

**Files:**
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularClient.java`
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularConfig.java`
- Test: `src/test/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularClientTest.java`

**Interfaces:**
- Consumes: `SpoonacularSearchResponse`, `QuotaExceededException`, `SpoonacularProperties` (Task 1); fixture from Task 2.
- Produces: `SpoonacularClient` with constructor `SpoonacularClient(RestClient restClient)` and method `SpoonacularSearchResponse searchByCuisine(String cuisine, int number, int offset)` (throws `QuotaExceededException` on 402, `RestClientException` family otherwise). `SpoonacularConfig` provides beans: `RestClient spoonacularRestClient(...)`, `SpoonacularClient spoonacularClient(...)`, `SpoonacularRecipeTranslator spoonacularRecipeTranslator()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularClientTest.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.HttpMethod.GET;

class SpoonacularClientTest {

    private MockRestServiceServer server;
    private SpoonacularClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.spoonacular.example")
                .defaultHeader("x-api-key", "test-key");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SpoonacularClient(builder.build());
    }

    private static String fixture() throws Exception {
        try (InputStream in = SpoonacularClientTest.class
                .getResourceAsStream("/spoonacular/complex-search-response.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void sendsCorrectRequestAndParsesResponse() throws Exception {
        server.expect(requestTo(startsWith("https://api.spoonacular.example/recipes/complexSearch")))
                .andExpect(method(GET))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(queryParam("cuisine", "italian"))
                .andExpect(queryParam("number", "40"))
                .andExpect(queryParam("offset", "0"))
                .andExpect(queryParam("addRecipeInformation", "true"))
                .andExpect(queryParam("addRecipeNutrition", "true"))
                .andExpect(queryParam("fillIngredients", "true"))
                .andExpect(queryParam("instructionsRequired", "true"))
                .andExpect(queryParam("sort", "popularity"))
                .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));

        SpoonacularSearchResponse response = client.searchByCuisine("italian", 40, 0);

        assertThat(response.results()).hasSize(2);
        assertThat(response.results().get(0).id()).isEqualTo(715538L);
        assertThat(response.results().get(0).nutrition().nutrients()).hasSize(5);
        server.verify();
    }

    @Test
    void quotaExhausted402ThrowsQuotaExceededException() {
        server.expect(requestTo(startsWith("https://api.spoonacular.example/recipes/complexSearch")))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED));

        assertThatThrownBy(() -> client.searchByCuisine("thai", 40, 0))
                .isInstanceOf(QuotaExceededException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=SpoonacularClientTest"`
Expected: BUILD FAILURE — `cannot find symbol: class SpoonacularClient`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularClient.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import org.springframework.web.client.RestClient;

/**
 * Thin wrapper around Spoonacular's complexSearch endpoint. One call per
 * cuisine returns full recipe info + nutrition + ingredients, so an entire
 * import run is ~10 HTTP requests. The API key travels in the x-api-key
 * header (set on the injected RestClient), never in the URL, so it can't
 * leak into logs.
 */
public class SpoonacularClient {

    private final RestClient restClient;

    public SpoonacularClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * @throws QuotaExceededException on HTTP 402 (daily points used up)
     */
    public SpoonacularSearchResponse searchByCuisine(String cuisine, int number, int offset) {
        return restClient.get()
                .uri(uri -> uri.path("/recipes/complexSearch")
                        .queryParam("cuisine", cuisine)
                        .queryParam("number", number)
                        .queryParam("offset", offset)
                        .queryParam("addRecipeInformation", "true")
                        .queryParam("addRecipeNutrition", "true")
                        .queryParam("fillIngredients", "true")
                        .queryParam("instructionsRequired", "true")
                        .queryParam("sort", "popularity")
                        .build())
                .retrieve()
                .onStatus(status -> status.value() == 402, (request, response) -> {
                    throw new QuotaExceededException();
                })
                .body(SpoonacularSearchResponse.class);
    }
}
```

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularConfig.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wiring for the Spoonacular integration. These beans always exist (they're
 * cheap and inert); only the import RUNNER is gated behind
 * spoonacular.import.enabled. An empty api key is fine here — the runner
 * validates it just-in-time before any request is made.
 */
@Configuration
public class SpoonacularConfig {

    @Bean
    public RestClient spoonacularRestClient(SpoonacularProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("x-api-key", properties.apiKey() != null ? properties.apiKey() : "")
                .build();
    }

    @Bean
    public SpoonacularClient spoonacularClient(RestClient spoonacularRestClient) {
        return new SpoonacularClient(spoonacularRestClient);
    }

    @Bean
    public SpoonacularRecipeTranslator spoonacularRecipeTranslator() {
        return new SpoonacularRecipeTranslator();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=SpoonacularClientTest"`
Expected: `Tests run: 2, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularClient.java src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularConfig.java src/test/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularClientTest.java
git commit -m "feat: spoonacular RestClient wrapper with quota handling"
```

---

### Task 4: Import service + persister + summary

**Files:**
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/ImportSummary.java`
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularRecipePersister.java`
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularImportService.java`
- Test: `src/test/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularImportServiceTest.java`

**Interfaces:**
- Consumes: `SpoonacularClient.searchByCuisine(String,int,int)` (Task 3), `SpoonacularRecipeTranslator.translate(Result,String)` → `TranslationResult(Recipe, List<RecipeIngredient>)` (Task 2), `SpoonacularImportProperties` (Task 1), `RecipeRepository.existsBySpoonacularId(Long)`, `RecipeIngredientRepository.saveAll(...)`.
- Produces:
  - `ImportSummary(List<CuisineResult> results, boolean quotaExhausted)` with nested `CuisineResult(String cuisine, int imported, int skipped, int failed)` and methods `totalImported()`, `totalSkipped()`, `totalFailed()`
  - `SpoonacularRecipePersister.persist(Recipe recipe, List<RecipeIngredient> ingredients)` — `@Transactional`, one transaction per recipe
  - `SpoonacularImportService.runImport()` → `ImportSummary` (Task 5's runner calls exactly this)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularImportServiceTest.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpoonacularImportServiceTest {

    @Mock
    SpoonacularClient client;
    @Mock
    SpoonacularRecipePersister persister;
    @Mock
    RecipeRepository recipeRepository;

    private SpoonacularImportService serviceWith(List<String> cuisines) {
        SpoonacularImportProperties props =
                new SpoonacularImportProperties(true, cuisines, 40, 0);
        return new SpoonacularImportService(client, new SpoonacularRecipeTranslator(),
                persister, recipeRepository, props);
    }

    private static SpoonacularSearchResponse responseWithIds(long... ids) {
        List<SpoonacularSearchResponse.Result> results = new java.util.ArrayList<>();
        for (long id : ids) {
            results.add(new SpoonacularSearchResponse.Result(
                    id, "Recipe " + id, null, 2, 20, null, null, null, null,
                    "Cook it.", null, false, false, false, false,
                    List.of(), null,
                    List.of(new SpoonacularSearchResponse.ExtendedIngredient(
                            "salt", null, "a pinch of salt", null))));
        }
        return new SpoonacularSearchResponse(results);
    }

    @Test
    void importsNewRecipesAndSkipsExisting() {
        when(client.searchByCuisine("italian", 40, 0)).thenReturn(responseWithIds(1L, 2L));
        when(recipeRepository.existsBySpoonacularId(1L)).thenReturn(true);   // already imported
        when(recipeRepository.existsBySpoonacularId(2L)).thenReturn(false);

        ImportSummary summary = serviceWith(List.of("italian")).runImport();

        assertThat(summary.totalImported()).isEqualTo(1);
        assertThat(summary.totalSkipped()).isEqualTo(1);
        assertThat(summary.totalFailed()).isZero();
        assertThat(summary.quotaExhausted()).isFalse();
        verify(persister).persist(any(Recipe.class), anyList());
    }

    @Test
    void quotaExhaustionStopsFurtherCuisinesButKeepsEarlierResults() {
        when(client.searchByCuisine("italian", 40, 0)).thenReturn(responseWithIds(1L));
        when(recipeRepository.existsBySpoonacularId(1L)).thenReturn(false);
        when(client.searchByCuisine("thai", 40, 0)).thenThrow(new QuotaExceededException());

        ImportSummary summary = serviceWith(List.of("italian", "thai", "greek")).runImport();

        assertThat(summary.totalImported()).isEqualTo(1);
        assertThat(summary.quotaExhausted()).isTrue();
        verify(client, never()).searchByCuisine(eq("greek"), anyInt(), anyInt());
    }

    @Test
    void oneFailingRecipeDoesNotAbortTheRun() {
        when(client.searchByCuisine("italian", 40, 0)).thenReturn(responseWithIds(1L, 2L));
        when(recipeRepository.existsBySpoonacularId(1L)).thenReturn(false);
        when(recipeRepository.existsBySpoonacularId(2L)).thenReturn(false);
        doThrow(new RuntimeException("boom")).doNothing()
                .when(persister).persist(any(Recipe.class), anyList());

        ImportSummary summary = serviceWith(List.of("italian")).runImport();

        assertThat(summary.totalImported()).isEqualTo(1);
        assertThat(summary.totalFailed()).isEqualTo(1);
    }

    @Test
    void networkErrorOnOneCuisineSkipsItAndContinues() {
        when(client.searchByCuisine(eq("italian"), anyInt(), anyInt()))
                .thenThrow(new RestClientException("connection reset"));
        when(client.searchByCuisine("thai", 40, 0)).thenReturn(responseWithIds(3L));
        when(recipeRepository.existsBySpoonacularId(3L)).thenReturn(false);

        ImportSummary summary = serviceWith(List.of("italian", "thai")).runImport();

        assertThat(summary.totalImported()).isEqualTo(1);
        assertThat(summary.results()).hasSize(2);
        assertThat(summary.results().get(0).failed()).isZero(); // cuisine skipped, not counted as recipe failures
    }

    @Test
    void nullResultsListIsTolerated() {
        lenient().when(client.searchByCuisine("italian", 40, 0))
                .thenReturn(new SpoonacularSearchResponse(null));

        ImportSummary summary = serviceWith(List.of("italian")).runImport();

        assertThat(summary.totalImported()).isZero();
        assertThat(summary.totalFailed()).isZero();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=SpoonacularImportServiceTest"`
Expected: BUILD FAILURE — `cannot find symbol: class SpoonacularImportService` (and `ImportSummary`, `SpoonacularRecipePersister`).

- [ ] **Step 3: Write the implementation**

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/ImportSummary.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import java.util.List;

/** Outcome of one import run, per cuisine and in total. */
public record ImportSummary(List<CuisineResult> results, boolean quotaExhausted) {

    public record CuisineResult(String cuisine, int imported, int skipped, int failed) {
    }

    public int totalImported() {
        return results.stream().mapToInt(CuisineResult::imported).sum();
    }

    public int totalSkipped() {
        return results.stream().mapToInt(CuisineResult::skipped).sum();
    }

    public int totalFailed() {
        return results.stream().mapToInt(CuisineResult::failed).sum();
    }
}
```

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularRecipePersister.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import mk.ukim.finki.wp.recipeappbackend.model.entities.Recipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeIngredientRepository;
import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One recipe + its ingredients in ONE transaction (same rule as
 * RecipeService.create: a crash halfway can't leave an ingredient-less
 * recipe). Separate class rather than a method on the import service
 * because @Transactional doesn't apply to self-invocation.
 */
@Component
public class SpoonacularRecipePersister {

    private final RecipeRepository recipeRepository;
    private final RecipeIngredientRepository ingredientRepository;

    public SpoonacularRecipePersister(RecipeRepository recipeRepository,
                                      RecipeIngredientRepository ingredientRepository) {
        this.recipeRepository = recipeRepository;
        this.ingredientRepository = ingredientRepository;
    }

    @Transactional
    public void persist(Recipe recipe, java.util.List<RecipeIngredient> ingredients) {
        recipeRepository.save(recipe);
        ingredientRepository.saveAll(ingredients);
    }
}
```

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularImportService.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import mk.ukim.finki.wp.recipeappbackend.repository.RecipeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates one import run. Isolation rules:
 * - quota exhaustion (402) stops FETCHING but keeps everything saved;
 * - a failing recipe is logged + counted, the run continues;
 * - a failing cuisine request is logged + skipped, the run continues.
 * Dedup: existsBySpoonacularId pre-check, with the DB's UNIQUE constraint
 * on spoonacular_id as the backstop.
 */
@Service
public class SpoonacularImportService {

    private static final Logger log = LoggerFactory.getLogger(SpoonacularImportService.class);

    private final SpoonacularClient client;
    private final SpoonacularRecipeTranslator translator;
    private final SpoonacularRecipePersister persister;
    private final RecipeRepository recipeRepository;
    private final SpoonacularImportProperties properties;

    public SpoonacularImportService(SpoonacularClient client,
                                    SpoonacularRecipeTranslator translator,
                                    SpoonacularRecipePersister persister,
                                    RecipeRepository recipeRepository,
                                    SpoonacularImportProperties properties) {
        this.client = client;
        this.translator = translator;
        this.persister = persister;
        this.recipeRepository = recipeRepository;
        this.properties = properties;
    }

    public ImportSummary runImport() {
        List<ImportSummary.CuisineResult> results = new ArrayList<>();
        boolean quotaExhausted = false;

        for (String cuisine : properties.cuisines()) {
            SpoonacularSearchResponse response;
            try {
                response = client.searchByCuisine(cuisine, properties.recipesPerCuisine(), properties.offset());
            } catch (QuotaExceededException e) {
                log.warn("Spoonacular quota exhausted at cuisine '{}' — stopping; saved work is kept", cuisine);
                quotaExhausted = true;
                break;
            } catch (RestClientException e) {
                log.error("Request for cuisine '{}' failed — skipping this cuisine", cuisine, e);
                results.add(new ImportSummary.CuisineResult(cuisine, 0, 0, 0));
                continue;
            }

            results.add(importCuisine(cuisine, response));
        }
        return new ImportSummary(results, quotaExhausted);
    }

    private ImportSummary.CuisineResult importCuisine(String cuisine, SpoonacularSearchResponse response) {
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        List<SpoonacularSearchResponse.Result> recipes =
                response.results() != null ? response.results() : List.of();

        for (SpoonacularSearchResponse.Result result : recipes) {
            try {
                if (recipeRepository.existsBySpoonacularId(result.id())) {
                    skipped++;
                    continue;
                }
                SpoonacularRecipeTranslator.TranslationResult translated =
                        translator.translate(result, cuisine);
                persister.persist(translated.recipe(), translated.ingredients());
                imported++;
            } catch (Exception e) {
                failed++;
                log.error("Import of spoonacular recipe {} failed — continuing", result.id(), e);
            }
        }
        return new ImportSummary.CuisineResult(cuisine, imported, skipped, failed);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=SpoonacularImportServiceTest"`
Expected: `Tests run: 5, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/ImportSummary.java src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularRecipePersister.java src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularImportService.java src/test/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularImportServiceTest.java
git commit -m "feat: spoonacular import orchestration with quota and failure isolation"
```

---

### Task 5: Runner + IntelliJ run config + full verification

**Files:**
- Create: `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularImportRunner.java`
- Modify: `.idea/workspace.xml` (add `SPOONACULAR_API_KEY` env placeholder next to `SUPABASE_DB_PASSWORD`)
- Test: `src/test/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularImportRunnerTest.java`

**Interfaces:**
- Consumes: `SpoonacularImportService.runImport()` → `ImportSummary` (Task 4), `SpoonacularProperties.apiKey()` (Task 1).
- Produces: the end-user entry point. Bean exists only when `spoonacular.import.enabled=true`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularImportRunnerTest.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpoonacularImportRunnerTest {

    @Mock
    SpoonacularImportService importService;

    @Test
    void missingApiKeyFailsFastWithoutCallingTheService() {
        SpoonacularImportRunner runner = new SpoonacularImportRunner(
                new SpoonacularProperties("", "https://api.spoonacular.com"), importService);

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPOONACULAR_API_KEY");
        verify(importService, never()).runImport();
    }

    @Test
    void runsImportAndSurvivesWhenKeyPresent() throws Exception {
        when(importService.runImport()).thenReturn(new ImportSummary(
                List.of(new ImportSummary.CuisineResult("italian", 3, 1, 0)), false));
        SpoonacularImportRunner runner = new SpoonacularImportRunner(
                new SpoonacularProperties("real-key", "https://api.spoonacular.com"), importService);

        runner.run(null);

        verify(importService).runImport();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=SpoonacularImportRunnerTest"`
Expected: BUILD FAILURE — `cannot find symbol: class SpoonacularImportRunner`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularImportRunner.java`:

```java
package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Entry point for the import. Exists ONLY when
 * spoonacular.import.enabled=true (pass --spoonacular.import.enabled=true
 * in the run configuration); normal app starts are completely unaffected.
 * Runs once at startup; the app keeps serving afterward.
 */
@Component
@ConditionalOnProperty(prefix = "spoonacular.import", name = "enabled", havingValue = "true")
public class SpoonacularImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SpoonacularImportRunner.class);

    private final SpoonacularProperties properties;
    private final SpoonacularImportService importService;

    public SpoonacularImportRunner(SpoonacularProperties properties,
                                   SpoonacularImportService importService) {
        this.properties = properties;
        this.importService = importService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "Spoonacular import is enabled but no API key is set. "
                            + "Set the SPOONACULAR_API_KEY environment variable "
                            + "(IntelliJ: Run > Edit Configurations > Environment variables).");
        }

        log.info("Starting Spoonacular import...");
        ImportSummary summary = importService.runImport();

        for (ImportSummary.CuisineResult r : summary.results()) {
            log.info("  {}: imported={}, skipped={}, failed={}",
                    r.cuisine(), r.imported(), r.skipped(), r.failed());
        }
        log.info("Spoonacular import finished: imported={}, skipped={}, failed={}",
                summary.totalImported(), summary.totalSkipped(), summary.totalFailed());
        if (summary.quotaExhausted()) {
            log.warn("Daily quota was exhausted mid-run. Everything fetched so far is saved. "
                    + "Re-run the same command after the quota resets — already-imported "
                    + "recipes are skipped automatically.");
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=SpoonacularImportRunnerTest"`
Expected: `Tests run: 2, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 5: Add the env-var placeholder to the IntelliJ run configuration**

In `.idea/workspace.xml` (this file is gitignored — do NOT commit it), inside the `<configuration name="RecipeAppBackendApplication" ...>` element's existing `<envs>` block, add one entry next to `SUPABASE_DB_PASSWORD`:

```xml
<env name="SPOONACULAR_API_KEY" value="PASTE_YOUR_KEY_HERE" />
```

The user replaces `PASTE_YOUR_KEY_HERE` with the real key. The blank/placeholder value is safe: the runner only checks the key when import is enabled, and `PASTE_YOUR_KEY_HERE` would simply produce a 401 from Spoonacular, not a crash at boot.

- [ ] **Step 6: Full verification — all importer tests + full existing suite**

Run: `.\mvnw.cmd test "-Dtest=SpoonacularPropertiesTest,SpoonacularRecipeTranslatorTest,SpoonacularClientTest,SpoonacularImportServiceTest,SpoonacularImportRunnerTest"`
Expected: `Tests run: 20, Failures: 0, Errors: 0` — BUILD SUCCESS.

Run: `.\mvnw.cmd test "-Dtest=CurrentUserIdArgumentResolverTest,GlobalExceptionHandlerTest,UserControllerTest,RecipeControllerTest,RatingControllerTest,CommentControllerTest,ReactionControllerTest"`
Expected: `Tests run: 46, Failures: 0, Errors: 0` — BUILD SUCCESS (regression guard: the dormant importer must not disturb the API slice tests).

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularImportRunner.java src/test/java/mk/ukim/finki/wp/recipeappbackend/spoonacular/SpoonacularImportRunnerTest.java
git commit -m "feat: property-gated spoonacular import runner"
```

Verify nothing else is left dirty: `git status --short` — expected: only gitignored noise (`.idea/workspace.xml` does not appear because it's ignored).
