package mk.ukim.finki.wp.recipeappbackend.generation;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rejects a post recipe whose description or steps copy the post's wording (spec section 4.2). Both sides are
 * lowercased, everything that is not a letter or digit becomes a space, and the rest is split into words. A run
 * of more than {@link #MAX_SHARED_RUN} consecutive words in common with the post text (the caption and the
 * reading's text, joined by the caller) is a copy. Ingredient lines and the title are not checked: "2 cups
 * flour" and a dish name naturally match.
 */
public final class OwnWordsCheck {

    /** 11 shared words in a row pass, 12 fail. */
    static final int MAX_SHARED_RUN = 11;

    private static final int WINDOW = MAX_SHARED_RUN + 1;
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private OwnWordsCheck() {
    }

    /**
     * @param postText the caption and the reading's text; null or blank passes
     * @return the first copied part: the description, then the steps in order; empty when nothing is copied
     */
    public static Optional<DraftProblem> check(RecipeDraft draft, String postText) {
        Set<String> postWindows = windows(words(postText));
        if (postWindows.isEmpty()) {
            return Optional.empty();
        }
        if (copies(draft.description(), postWindows)) {
            return Optional.of(problem("The description"));
        }
        List<String> steps = draft.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (copies(steps.get(i), postWindows)) {
                return Optional.of(problem("Step " + (i + 1)));
            }
        }
        return Optional.empty();
    }

    private static DraftProblem problem(String part) {
        return new DraftProblem(part + " copies the post's wording. Rewrite it in your own words.");
    }

    private static boolean copies(String text, Set<String> postWindows) {
        String[] words = words(text);
        for (int i = 0; i + WINDOW <= words.length; i++) {
            if (postWindows.contains(window(words, i))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> windows(String[] words) {
        Set<String> windows = new HashSet<>();
        for (int i = 0; i + WINDOW <= words.length; i++) {
            windows.add(window(words, i));
        }
        return windows;
    }

    private static String window(String[] words, int start) {
        return String.join(" ", Arrays.copyOfRange(words, start, start + WINDOW));
    }

    private static String[] words(String text) {
        if (text == null) {
            return new String[0];
        }
        String spaced = NOT_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
        return spaced.isEmpty() ? new String[0] : spaced.split(" ");
    }
}
