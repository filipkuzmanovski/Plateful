package mk.ukim.finki.wp.recipeappbackend.model.projection;

/** Number of recipes tagged with a cuisine key, from the unnested cuisines array. */
public interface CuisineCount {
    String getKey();

    long getCount();
}
