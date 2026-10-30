package mk.ukim.finki.wp.recipeappbackend.food;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Allergen and diet rules loaded from {@code food-rules.csv}, the file shared with
 * {@code tools/dataset/rules.py}. Columns: {@code kind,key,label,terms,exceptions}; list items are
 * separated by {@code |}, and a term written {@code @key} imports that row's groups.
 *
 * <p>Each row resolves to groups of (terms, exceptions), one per source row: its own terms with its
 * own exceptions, plus the groups of every referenced row. Exceptions therefore only mask terms of
 * the row that defines them.
 *
 * <p>A term matches a name when {@code " " + name + " "} contains {@code " " + term + " "}. A name hits
 * a row when some group has a matching term and none of that group's exceptions match the name.
 *
 * <p>Meal rows are validated but not used here; the backend does not tag meals.
 */
@Component
public class FoodRules {

    private static final String RESOURCE = "/food-rules.csv";
    private static final String HEADER = "kind,key,label,terms,exceptions";
    private static final Set<String> KINDS = Set.of("allergen", "diet", "meal");

    private record Group(Set<String> terms, Set<String> exceptions) {
    }

    private static final class Row {
        final String kind;
        final String key;
        final String label;
        final List<String> rawTerms;
        final List<String> rawExceptions;
        List<Group> groups = List.of();

        Row(String kind, String key, String label, List<String> rawTerms, List<String> rawExceptions) {
            this.kind = kind;
            this.key = key;
            this.label = label;
            this.rawTerms = rawTerms;
            this.rawExceptions = rawExceptions;
        }

        boolean hits(String padded) {
            for (Group group : groups) {
                if (matches(padded, group.terms()) && !matches(padded, group.exceptions())) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<Row> rows;
    private final Map<String, Row> byKey;
    private final List<Allergen> allergens;
    private final Set<String> allergenKeys;
    private final Set<String> dietKeys;

    /** Loads the shipped {@code /food-rules.csv} from the classpath; fails fast when it is broken. */
    @Autowired
    public FoodRules() {
        this(new StringReader(readClasspathResource()));
    }

    /** Parses rules from {@code reader} (the caller closes it); fails fast with {@link IllegalStateException}. */
    public FoodRules(Reader reader) {
        this.rows = parse(reader);
        Map<String, Row> index = new HashMap<>();
        for (Row row : rows) {
            index.put(row.key, row);
        }
        this.byKey = index;
        Map<String, List<Group>> cache = new HashMap<>();
        for (Row row : rows) {
            row.groups = resolve(row.key, List.of(), cache);
        }
        this.allergens = rows.stream().filter(r -> r.kind.equals("allergen"))
                .map(r -> new Allergen(r.key, r.label)).toList();
        this.allergenKeys = keysOf("allergen");
        this.dietKeys = keysOf("diet");
    }

    /** The allergens, in file order. */
    public List<Allergen> allergens() {
        return allergens;
    }

    public Set<String> allergenKeys() {
        return allergenKeys;
    }

    public Set<String> dietKeys() {
        return dietKeys;
    }

    /** The allergen keys present in {@code names}; names that normalise to nothing are ignored. */
    public Set<String> allergensIn(Collection<String> names) {
        List<String> padded = paddedNames(names);
        Set<String> found = new LinkedHashSet<>();
        for (Row row : rows) {
            if (row.kind.equals("allergen") && padded.stream().anyMatch(row::hits)) {
                found.add(row.key);
            }
        }
        return found;
    }

    /** The diets among {@code diets} that {@code names} break; names that normalise to nothing are ignored. */
    public Set<String> dietViolations(Collection<String> names, Set<String> diets) {
        List<String> padded = paddedNames(names);
        Set<String> broken = new LinkedHashSet<>();
        for (Row row : rows) {
            if (row.kind.equals("diet") && diets.contains(row.key) && padded.stream().anyMatch(row::hits)) {
                broken.add(row.key);
            }
        }
        return broken;
    }

    private Set<String> keysOf(String kind) {
        Set<String> keys = new LinkedHashSet<>();
        for (Row row : rows) {
            if (row.kind.equals(kind)) {
                keys.add(row.key);
            }
        }
        return Set.copyOf(keys);
    }

    private static List<String> paddedNames(Collection<String> names) {
        Set<String> normalised = new LinkedHashSet<>();
        for (String name : names) {
            IngredientNames.normalise(name).ifPresent(normalised::add);
        }
        return normalised.stream().map(n -> " " + n + " ").toList();
    }

    private static boolean matches(String padded, Set<String> phrases) {
        for (String phrase : phrases) {
            if (padded.contains(" " + phrase + " ")) {
                return true;
            }
        }
        return false;
    }

    // --- resolving @references ----------------------------------------------------

    private List<Group> resolve(String key, List<String> stack, Map<String, List<Group>> cache) {
        List<Group> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (stack.contains(key)) {
            List<String> cycle = new ArrayList<>(stack);
            cycle.add(key);
            throw new IllegalStateException(RESOURCE + ": reference cycle: " + String.join(" -> ", cycle));
        }
        Row row = byKey.get(key);
        List<String> nextStack = new ArrayList<>(stack);
        nextStack.add(key);
        Set<String> ownTerms = new LinkedHashSet<>();
        Set<String> ownExceptions = new LinkedHashSet<>();
        List<Group> imported = new ArrayList<>();
        for (String item : row.rawTerms) {
            if (item.startsWith("@")) {
                String ref = item.substring(1);
                if (!byKey.containsKey(ref)) {
                    throw new IllegalStateException(RESOURCE + ": " + key + ": unknown reference '" + item + "'");
                }
                imported.addAll(resolve(ref, nextStack, cache));
            } else {
                ownTerms.add(normalisedItem(key, item));
            }
        }
        for (String item : row.rawExceptions) {
            if (item.startsWith("@")) {
                throw new IllegalStateException(
                        RESOURCE + ": " + key + ": references are not allowed in exceptions: '" + item + "'");
            }
            ownExceptions.add(normalisedItem(key, item));
        }
        List<Group> groups = new ArrayList<>();
        if (!ownTerms.isEmpty()) {
            groups.add(new Group(Set.copyOf(ownTerms), Set.copyOf(ownExceptions)));
        }
        groups.addAll(imported);
        List<Group> result = List.copyOf(groups);
        cache.put(key, result);
        return result;
    }

    private static String normalisedItem(String key, String item) {
        return IngredientNames.normalise(item).orElseThrow(
                () -> new IllegalStateException(RESOURCE + ": " + key + ": '" + item + "' normalises to nothing"));
    }

    // --- parsing ---------------------------------------------------------------------

    private static List<Row> parse(Reader reader) {
        List<Row> parsed = new ArrayList<>();
        Map<String, Row> seen = new LinkedHashMap<>();
        try {
            BufferedReader lines = new BufferedReader(reader);
            String header = lines.readLine();
            if (header != null && header.startsWith("﻿")) {
                header = header.substring(1);
            }
            if (header == null || !header.strip().equals(HEADER)) {
                throw new IllegalStateException(RESOURCE + ": expected header " + HEADER);
            }
            int lineNo = 1;
            String line;
            while ((line = lines.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split(",", 5);
                if (f.length != 5) {
                    throw new IllegalStateException(RESOURCE + " line " + lineNo + ": expected 5 fields, got " + f.length);
                }
                String kind = f[0].strip();
                String key = f[1].strip();
                if (!KINDS.contains(kind)) {
                    throw new IllegalStateException(RESOURCE + " line " + lineNo + ": unknown kind '" + kind + "'");
                }
                if (key.isEmpty() || seen.containsKey(key)) {
                    throw new IllegalStateException(RESOURCE + " line " + lineNo + ": missing or duplicate key '" + key + "'");
                }
                Row row = new Row(kind, key, f[2].strip(), splitItems(f[3]), splitItems(f[4]));
                seen.put(key, row);
                parsed.add(row);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RESOURCE, e);
        }
        return List.copyOf(parsed);
    }

    private static List<String> splitItems(String field) {
        List<String> items = new ArrayList<>();
        for (String raw : field.split("\\|")) {
            String item = raw.strip();
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    private static String readClasspathResource() {
        try (InputStream in = FoodRules.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RESOURCE, e);
        }
    }
}
