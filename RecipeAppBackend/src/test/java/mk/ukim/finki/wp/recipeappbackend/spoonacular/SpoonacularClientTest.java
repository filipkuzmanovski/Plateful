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
