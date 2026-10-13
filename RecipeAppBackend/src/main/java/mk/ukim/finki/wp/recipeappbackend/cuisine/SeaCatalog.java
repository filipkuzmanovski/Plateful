package mk.ukim.finki.wp.recipeappbackend.cuisine;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The five seas with their descriptive text, loaded from {@code /seas.json} in file order.
 */
@Component
public class SeaCatalog {

    private static final String RESOURCE = "/seas.json";

    private final List<Sea> seas;

    public SeaCatalog() {
        List<Sea> loaded;
        try (InputStream in = SeaCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            loaded = JsonMapper.builder().build().readValue(in, new TypeReference<List<Sea>>() { });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RESOURCE, e);
        }
        Set<String> seen = new HashSet<>();
        for (Sea s : loaded) {
            if (!seen.add(s.key())) {
                throw new IllegalStateException(RESOURCE + ": duplicate sea key '" + s.key() + "'");
            }
        }
        if (!seen.equals(CuisineCatalog.SEA_KEYS)) {
            throw new IllegalStateException(RESOURCE + ": keys " + seen + " must be exactly " + CuisineCatalog.SEA_KEYS);
        }
        this.seas = List.copyOf(loaded);
    }

    public List<Sea> all() {
        return seas;
    }
}
