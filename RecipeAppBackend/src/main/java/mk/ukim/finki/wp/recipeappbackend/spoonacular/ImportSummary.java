package mk.ukim.finki.wp.recipeappbackend.spoonacular;

import java.util.List;

/** Outcome of one import run, per cuisine and in total ({@code deleted} = imported rows removed by replace mode). */
public record ImportSummary(List<CuisineResult> results, boolean quotaExhausted, int deleted) {

    public record CuisineResult(String cuisine, int imported, int skipped, int failed) {
    }

    public int totalImported() {
        return results.stream().mapToInt(CuisineResult::imported).sum();
    }

    public int totalSkipped() {
        return results.stream().mapToInt(CuisineResult::skipped).sum();
    }

    public int totalFailed() {
        return results.stream().mapToInt(CuisineResult::failed).sum();
    }
}
