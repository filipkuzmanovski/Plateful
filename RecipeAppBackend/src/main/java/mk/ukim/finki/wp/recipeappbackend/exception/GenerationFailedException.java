package mk.ukim.finki.wp.recipeappbackend.exception;

/** Every attempt failed the structure, safety or novelty checks (or the time budget ran out). Maps to 422. */
public class GenerationFailedException extends RuntimeException {

    public GenerationFailedException() {
        super("Couldn't make a safe new recipe from these. Try changing the ingredients.");
    }
}
