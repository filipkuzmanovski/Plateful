package mk.ukim.finki.wp.recipeappbackend.food;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The single definition of an "ingredient name" in the backend. It is a twin of
 * {@code tools/dataset/normalise.py} and must give identical output; both read the same
 * irregular plurals ({@code ingredient-irregulars.csv}) and are tested against the same
 * cases ({@code tools/dataset/normalise-cases.csv}).
 */
public final class IngredientNames {

    public static final int MAX_NAME_LENGTH = 60;

    private static final String IRREGULARS_RESOURCE = "/ingredient-irregulars.csv";
    private static final Map<String, String> IRREGULARS = loadIrregulars();

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    // Python's \s on str is Unicode-aware (and includes the ASCII separators \x1c-\x1f).
    private static final Pattern WHITESPACE = Pattern.compile("(?U)[\\s\\x1c-\\x1f]");
    private static final Pattern NOT_LETTER_OR_SPACE = Pattern.compile("[^a-z ]");
    private static final Pattern SPACES = Pattern.compile(" +");

    private IngredientNames() {
    }

    /** Ingredient form: singularises the last word. Empty when the result is empty or over 60 characters. */
    public static Optional<String> normalise(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String[] words = clean(raw);
        if (words.length == 0) {
            return Optional.empty();
        }
        words[words.length - 1] = singularise(words[words.length - 1]);
        String result = String.join(" ", words);
        if (result.isEmpty() || result.length() > MAX_NAME_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    private static String[] clean(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        String stripped = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String lowered = stripped.toLowerCase(Locale.ROOT).replace('-', ' ');
        String spaced = WHITESPACE.matcher(lowered).replaceAll(" ");
        String lettersOnly = NOT_LETTER_OR_SPACE.matcher(spaced).replaceAll("");
        // A lone "s" is a possessive remnant (e.g. an acute accent standing in for an apostrophe); drop it.
        return SPACES.splitAsStream(lettersOnly.strip())
                .filter(w -> !w.isEmpty() && !w.equals("s"))
                .toArray(String[]::new);
    }

    private static String singularise(String word) {
        String irregular = IRREGULARS.get(word);
        if (irregular != null) {
            return irregular;
        }
        if (word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("oes")
                || word.endsWith("ches") || word.endsWith("shes") || word.endsWith("sses") || word.endsWith("xes")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static Map<String, String> loadIrregulars() {
        Map<String, String> irregulars = new HashMap<>();
        try (InputStream in = IngredientNames.class.getResourceAsStream(IRREGULARS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + IRREGULARS_RESOURCE);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line != null && line.startsWith("﻿")) {
                line = line.substring(1);
            }
            if (line == null || !line.strip().equals("plural,singular")) {
                throw new IllegalStateException(IRREGULARS_RESOURCE + ": expected header plural,singular");
            }
            int lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split(",", -1);
                if (fields.length != 2) {
                    throw new IllegalStateException(IRREGULARS_RESOURCE + " line " + lineNo + ": expected 2 fields");
                }
                irregulars.put(fields[0].strip(), fields[1].strip());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + IRREGULARS_RESOURCE, e);
        }
        return Map.copyOf(irregulars);
    }
}
