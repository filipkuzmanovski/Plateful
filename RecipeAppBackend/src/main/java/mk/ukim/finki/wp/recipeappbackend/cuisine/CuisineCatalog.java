package mk.ukim.finki.wp.recipeappbackend.cuisine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Curated, classpath-loaded catalogue of cuisines ({@code /cuisines.csv}).
 */
@Component
public class CuisineCatalog {

    private static final String RESOURCE = "/cuisines.csv";
    private static final String MEDITERRANEAN = "mediterranean";

    public static final Set<String> SEA_KEYS =
            Set.of("mediterranean", "caribbean", "north_sea", "pacific", "gulf_of_mexico");

    private final List<CuisineEntry> entries;
    private final Map<String, CuisineEntry> byKey;

    public CuisineCatalog() {
        List<CuisineEntry> loaded = new ArrayList<>();
        Map<String, CuisineEntry> index = new LinkedHashMap<>();
        try (InputStream in = CuisineCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line = reader.readLine(); // header
            int lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                CuisineEntry entry = parseLine(line, lineNo);
                String key = entry.key();
                if (index.putIfAbsent(key, entry) != null) {
                    throw new IllegalStateException("Duplicate cuisine key '" + key + "'");
                }
                loaded.add(entry);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RESOURCE, e);
        }
        this.entries = List.copyOf(loaded);
        this.byKey = Map.copyOf(index);
    }

    /** Parses one {@code key,label,region,seas} row; rejects an unknown region or sea key. */
    static CuisineEntry parseLine(String line, int lineNo) {
        String[] f = line.split(",", -1);
        if (f.length != 4) {
            throw new IllegalStateException(RESOURCE + " line " + lineNo + ": expected 4 fields");
        }
        String key = f[0].trim();
        Region region = Region.fromKey(f[2])
                .orElseThrow(() -> new IllegalStateException("Unknown region '" + f[2] + "' for cuisine '" + key + "'"));
        List<String> seas = new ArrayList<>();
        for (String raw : f[3].split("\\|")) {
            String sea = raw.trim();
            if (sea.isEmpty()) {
                continue;
            }
            if (!SEA_KEYS.contains(sea)) {
                throw new IllegalStateException(RESOURCE + " line " + lineNo + ": unknown sea '" + sea + "' for cuisine '" + key + "'");
            }
            seas.add(sea);
        }
        return new CuisineEntry(key, f[1].trim(), region, List.copyOf(seas));
    }

    public List<CuisineEntry> all() {
        return entries;
    }

    public Optional<CuisineEntry> find(String key) {
        return Optional.ofNullable(byKey.get(normalize(key)));
    }

    public boolean contains(String key) {
        return byKey.containsKey(normalize(key));
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        // Collapse ASCII and Unicode whitespace (incl. NBSP) first so trim() sees plain spaces at the ends.
        return raw.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}]+", " ").trim();
    }

    public Set<String> keysForRegion(String regionKey) {
        String k = normalize(regionKey);
        Set<String> keys = new LinkedHashSet<>();
        if (MEDITERRANEAN.equals(k)) {
            // Backward compatible alias: region=mediterranean is the Mediterranean sea.
            return keysForSea(MEDITERRANEAN);
        }
        Optional<Region> region = Region.fromKey(k);
        if (region.isPresent()) {
            for (CuisineEntry e : entries) {
                if (e.region() == region.get()) {
                    keys.add(e.key());
                }
            }
        }
        return keys;
    }

    public Set<String> keysForSea(String seaKey) {
        String k = normalize(seaKey);
        Set<String> keys = new LinkedHashSet<>();
        for (CuisineEntry e : entries) {
            if (e.seas().contains(k)) {
                keys.add(e.key());
            }
        }
        return keys;
    }

    /**
     * Empty means "no cuisine filter". A present (possibly empty) set means "match only these keys".
     */
    public Optional<Set<String>> resolveFilter(List<String> cuisines, List<String> regions, List<String> seas) {
        List<String> cs = nonBlank(cuisines);
        List<String> rs = nonBlank(regions);
        List<String> ss = nonBlank(seas);
        if (cs.isEmpty() && rs.isEmpty() && ss.isEmpty()) {
            return Optional.empty();
        }
        Set<String> union = new LinkedHashSet<>();
        for (String c : cs) {
            String n = normalize(c);
            if (byKey.containsKey(n)) {
                union.add(n);
            }
        }
        for (String r : rs) {
            union.addAll(keysForRegion(r));
        }
        for (String s : ss) {
            union.addAll(keysForSea(s));
        }
        return Optional.of(union);
    }

    public List<String> normalizeAndValidate(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String r : raw) {
            String n = normalize(r);
            if (!n.isEmpty()) {
                normalized.add(n);
            }
        }
        List<String> unknown = normalized.stream().filter(k -> !byKey.containsKey(k)).toList();
        if (!unknown.isEmpty()) {
            throw new InvalidCuisineException(unknown);
        }
        return List.copyOf(normalized);
    }

    private static List<String> nonBlank(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(v -> v != null && !v.isBlank()).toList();
    }
}
