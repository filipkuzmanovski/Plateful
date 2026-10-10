package mk.ukim.finki.wp.recipeappbackend.cuisine;

import java.util.Locale;
import java.util.Optional;

public enum Region {
    EUROPE, ASIA, AMERICAS, AFRICA, MIDDLE_EAST, OCEANIA;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Region> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String k = key.trim();
        for (Region r : values()) {
            if (r.key().equals(k)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
