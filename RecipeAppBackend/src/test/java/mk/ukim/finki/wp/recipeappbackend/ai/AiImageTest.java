package mk.ukim.finki.wp.recipeappbackend.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiImageTest {

    @Test
    void toString_hasNoBytes() {
        AiImage image = new AiImage("image/jpeg", new byte[] {1, 2, 3});

        assertThat(image.toString()).isEqualTo("AiImage[mimeType=image/jpeg, bytes=3]");
        assertThat(image.toString()).doesNotContain("[B@");
    }

    @Test
    void request_fiveArgConstructor_hasNoImages() {
        AiRequest request = new AiRequest("S", "U", Map.of(), 0.2, 100);

        assertThat(request.images()).isEmpty();
    }

    @Test
    void request_nullImagesBecomeEmpty_andTheListIsCopied() {
        assertThat(new AiRequest("S", "U", Map.of(), 0.2, 100, null).images()).isEmpty();

        List<AiImage> source = new ArrayList<>(List.of(new AiImage("image/jpeg", new byte[] {1})));
        AiRequest request = new AiRequest("S", "U", Map.of(), 0.2, 100, source);
        source.clear();

        assertThat(request.images()).hasSize(1);
    }
}
