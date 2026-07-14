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
