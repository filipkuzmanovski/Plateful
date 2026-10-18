package mk.ukim.finki.wp.recipeappbackend.model.displayDTO;

/**
 * One row of the Saved list: kind "recipe" carries a normal card, kind "stub" carries a stub.
 * Exactly one of recipe and stub is non-null; the other serialises as null.
 */
public record SavedItemDto(String kind, RecipeDisplayDto recipe, SavedRecipeStubDto stub) {

    public static SavedItemDto ofRecipe(RecipeDisplayDto recipe) {
        return new SavedItemDto("recipe", recipe, null);
    }

    public static SavedItemDto ofStub(SavedRecipeStubDto stub) {
        return new SavedItemDto("stub", null, stub);
    }
}
