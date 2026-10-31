package mk.ukim.finki.wp.recipeappbackend.ai;

/** One structured-JSON completion from an AI provider. Providers know nothing about recipes. */
public interface RecipeAi {

    AiReply complete(AiRequest request);
}
