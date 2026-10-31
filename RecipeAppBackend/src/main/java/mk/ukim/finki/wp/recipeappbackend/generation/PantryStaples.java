package mk.ukim.finki.wp.recipeappbackend.generation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The fixed list of pantry staples from {@code pantry-staples.txt} (one normalised ingredient name per
 * line): things an AI recipe may use without the user having listed them.
 */
@Component
public class PantryStaples {

    private static final String RESOURCE = "/pantry-staples.txt";

    private final Set<String> names;

    public PantryStaples() {
        Set<String> loaded = new LinkedHashSet<>();
        try (InputStream in = PantryStaples.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String name = line.strip();
                if (name.startsWith("\uFEFF")) {
                    name = name.substring(1);
                }
                if (!name.isEmpty()) {
                    loaded.add(name);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RESOURCE, e);
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " is empty");
        }
        this.names = Collections.unmodifiableSet(loaded);
    }

    /** The staple names, in file order. */
    public Set<String> names() {
        return names;
    }

    /** Whether {@code normalisedName} (an {@code IngredientNames.normalise} result) is a staple. */
    public boolean isStaple(String normalisedName) {
        return normalisedName != null && names.contains(normalisedName);
    }
}
