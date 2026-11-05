package mk.ukim.finki.wp.recipeappbackend.nutrition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import mk.ukim.finki.wp.recipeappbackend.generation.StoredRecipe;
import mk.ukim.finki.wp.recipeappbackend.model.entities.RecipeIngredient;

/** The ingredient lines sent to the AI, one per ingredient, each cut to 200 characters. */
public final class NutritionLines {

    static final int MAX_LINE = 200;

    private NutritionLines() {
    }

    public static List<String> forRecipe(List<RecipeIngredient> ingredients) {
        List<String> lines = new ArrayList<>();
        for (RecipeIngredient i : ingredients) {
            lines.add(line(i.getIngredientName(), i.getOriginalText()));
        }
        return lines;
    }

    public static List<String> forGenerated(StoredRecipe recipe) {
        List<String> lines = new ArrayList<>();
        if (recipe.ingredients() == null) {
            return lines;
        }
        for (StoredRecipe.StoredIngredient i : recipe.ingredients()) {
            lines.add(line(i.name(), i.amount()));
        }
        return lines;
    }

    /** {@code "1. a\n2. b"}, counting from 1. */
    public static String numbered(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(i + 1).append(". ").append(lines.get(i));
        }
        return out.toString();
    }

    /** SHA-256 of the numbered lines as UTF-8, lower-case hex. */
    public static String hash(List<String> lines) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(numbered(lines).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String line(String name, String text) {
        String n = name == null ? "" : name.strip();
        String t = text == null ? "" : text.strip();
        String line = t.isEmpty() ? n : n + " | " + t;
        return line.length() > MAX_LINE ? line.substring(0, MAX_LINE) : line;
    }
}
