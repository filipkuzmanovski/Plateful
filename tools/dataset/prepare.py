"""Turn RecipeNLG's full_dataset.csv into the three CSVs loaded into Postgres.

    python tools/dataset/prepare.py [--input PATH] [--out DIR]
                                    [--min-common 50] [--per-meal 15000] [--untagged 5000]

Three streaming passes over the input (the file is ~2 GB, so it is re-read, never held):
  1. count recipes per normalised ingredient name           -> ingredient_stats.csv
  2. count pairs among "common" ingredients, keep top 30    -> ingredient_pairings.csv
  3. tag, filter and sample recipes (seeded, reproducible)  -> reference_recipes.csv

Standard library only (plus the sibling normalise.py and rules.py).
"""

import argparse
import csv
import heapq
import json
import random
import sys
import time
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from normalise import REPO_ROOT, normalise, normalise_words
from rules import FoodRules

DEFAULT_INPUT = REPO_ROOT / "tools/dataset/data/full_dataset.csv"
DEFAULT_OUT = REPO_ROOT / "tools/dataset/out"
README = "tools/dataset/README.md"

MEALS = ("breakfast", "lunch", "dinner")  # bucket order: a multi-tag recipe fills the first one with room
SEED = 20261030
TOP_PARTNERS = 30
MIN_STATS_COUNT = 5
PROGRESS_EVERY = 100_000
MAX_FIELD_SIZE = 10 * 1024 * 1024  # a stray unbalanced quote then fails fast instead of swallowing the file
REQUIRED_COLUMNS = ("", "title", "ingredients", "directions", "NER")

MAX_TITLE_LENGTH = 255
MIN_LINES, MAX_LINES = 3, 20
MIN_DIRECTIONS, MAX_DIRECTIONS = 2, 25
MAX_DIRECTION_LENGTH = 2000

RECIPE_COLUMNS = [
    "id", "title", "ingredient_lines", "ingredient_names", "directions",
    "meal_types", "allergens", "vegetarian", "vegan", "gluten_free", "dairy_free",
]


@dataclass
class ParsedRow:
    id: int
    title: str
    ingredient_lines: list
    directions: list
    ingredient_names: list  # NER names, normalised, de-duplicated, first-seen order


@dataclass
class RefRecipe:
    id: int
    title: str
    ingredient_lines: list
    ingredient_names: list
    directions: list
    meal_types: list
    allergens: list
    vegetarian: bool
    vegan: bool
    gluten_free: bool
    dairy_free: bool


# --- reading -----------------------------------------------------------------

class InputError(Exception):
    """The input file is not a usable RecipeNLG csv."""


def _string_list(text):
    value = json.loads(text)
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        raise ValueError("not a list of strings")
    return value


def _parse(record):
    names, seen = [], set()
    for raw in _string_list(record["NER"]):
        name = normalise(raw)
        if name is not None and name not in seen:
            seen.add(name)
            names.append(name)
    return ParsedRow(
        id=int(record[""]),
        title=record["title"],
        ingredient_lines=_string_list(record["ingredients"]),
        directions=_string_list(record["directions"]),
        ingredient_names=names,
    )


def read_rows(path, stats):
    """Stream ParsedRows from a RecipeNLG csv. Malformed rows are skipped and counted in stats["skipped"].

    Raises InputError before reading any row if the header lacks a required column.
    """
    csv.field_size_limit(MAX_FIELD_SIZE)
    with open(path, encoding="utf-8-sig", errors="replace", newline="") as f:
        reader = csv.DictReader(f)
        try:
            header = reader.fieldnames
        except csv.Error as error:
            raise InputError(f"Cannot read the header of {path}: {error}") from error
        missing = [c for c in REQUIRED_COLUMNS if c not in (header or [])]
        if missing:
            expected = ", ".join(repr(c) for c in REQUIRED_COLUMNS)
            raise InputError(f"{path} does not look like RecipeNLG's full_dataset.csv: "
                             f"expected the columns {expected}, but it has {header or 'no header'}.")
        while True:
            try:
                record = next(reader)
            except StopIteration:
                return
            except csv.Error:
                stats["skipped"] += 1
                continue
            try:
                row = _parse(record)
            except (KeyError, TypeError, ValueError):  # JSONDecodeError is a ValueError
                stats["skipped"] += 1
                continue
            stats["rows"] += 1
            yield row


# --- pass 1: ingredient counts -----------------------------------------------

def count_ingredients(rows):
    counts = Counter()
    for row in rows:
        counts.update(row.ingredient_names)  # names are already unique within a recipe
    return counts


# --- pass 2: pairings --------------------------------------------------------

def count_pairs(rows, common, top_n=TOP_PARTNERS, stats=None):
    """Top partners per common ingredient: {name: [(partner, together_count), ...]}.

    Each list is sorted by count descending, then partner name. Pairs are counted once per
    recipe as integer keys (a * N + b) to keep memory down. If `stats` is given, stats["pairs"] is
    kept at the number of distinct pairs seen so far.
    """
    names = sorted(common)
    index = {name: i for i, name in enumerate(names)}
    n = len(names)
    pair_counts = Counter()
    for row in rows:
        ids = sorted(index[name] for name in row.ingredient_names if name in index)
        for i, a in enumerate(ids):
            base = a * n
            for b in ids[i + 1:]:
                pair_counts[base + b] += 1
        if stats is not None:
            stats["pairs"] = len(pair_counts)

    # Ids follow name order, so for equal counts a larger partner id is the worse entry.
    heaps = {}
    for key, count in pair_counts.items():
        a, b = divmod(key, n)
        for owner, partner in ((a, b), (b, a)):
            heap = heaps.setdefault(owner, [])
            entry = (count, -partner)
            if len(heap) < top_n:
                heapq.heappush(heap, entry)
            elif entry > heap[0]:
                heapq.heapreplace(heap, entry)

    return {
        names[owner]: [(names[-neg], count) for count, neg in sorted(heap, reverse=True)]
        for owner, heap in sorted(heaps.items())
    }


# --- pass 3: tag, filter, sample ---------------------------------------------

def _keep(row):
    title = row.title.strip()
    return (
        0 < len(title) <= MAX_TITLE_LENGTH
        and MIN_LINES <= len(row.ingredient_lines) <= MAX_LINES
        and MIN_DIRECTIONS <= len(row.directions) <= MAX_DIRECTIONS
        and all(len(d) <= MAX_DIRECTION_LENGTH for d in row.directions)
        and bool(row.ingredient_names)
    )


class _Pool:
    """Keeps the `capacity` recipes with the smallest random keys."""

    def __init__(self, capacity):
        self.capacity = capacity
        self._heap = []  # (-key, -seq, recipe): the root is the largest key

    def admits(self, key):
        return self.capacity > 0 and (len(self._heap) < self.capacity or key < -self._heap[0][0])

    def add(self, key, seq, recipe):
        entry = (-key, -seq, recipe)
        if len(self._heap) < self.capacity:
            heapq.heappush(self._heap, entry)
        else:
            heapq.heapreplace(self._heap, entry)

    def ranked(self):
        return [recipe for _, _, recipe in sorted(self._heap, key=lambda e: (-e[0], -e[1]))]


def select_recipes(rows, rules, per_meal, untagged, seed, stats=None):
    """Filter, tag and sample recipes (seeded, so the same input gives the same result).

    Every kept recipe gets a random key. Each meal bucket takes its smallest-keyed candidates;
    a recipe tagged for several meals goes to the first bucket (breakfast, lunch, dinner) that
    takes it. Buckets that come up short are topped up from the untagged recipes. Memory is
    bounded: a pool only ever holds the candidates its bucket could still need.
    """
    rng = random.Random(seed)
    # Bucket i can lose at most i * per_meal candidates to earlier buckets.
    meal_pools = {meal: _Pool(per_meal * (i + 1)) for i, meal in enumerate(MEALS)}
    plain_pool = _Pool(untagged + per_meal * len(MEALS))  # room for the largest possible top-up
    seen_titles = set()
    seq = 0

    for row in rows:
        if not _keep(row):
            continue
        title = row.title.strip()
        title_key = normalise_words(title) or title.lower()
        if title_key in seen_titles:
            continue
        seen_titles.add(title_key)

        seq += 1
        key = rng.random()
        tags = rules.meal_types(title)
        pools = [meal_pools[m] for m in MEALS if m in tags] or [plain_pool]
        if not any(pool.admits(key) for pool in pools):
            continue
        recipe = _build(row, title, tags, rules)
        for pool in pools:
            if pool.admits(key):
                pool.add(key, seq, recipe)

    chosen, taken = [], set()
    for meal in MEALS:
        picked = [r for r in meal_pools[meal].ranked() if r.id not in taken][:per_meal]
        taken.update(r.id for r in picked)
        chosen.extend(picked)
        if stats is not None:
            stats[meal] = len(picked)
    shortfall = per_meal * len(MEALS) - len(chosen)
    plain = plain_pool.ranked()[: untagged + shortfall]
    chosen.extend(plain)
    if stats is not None:
        stats["untagged"] = len(plain)
    chosen.sort(key=lambda r: r.id)
    return chosen


def _build(row, title, tags, rules):
    flags = rules.diet_flags(row.ingredient_names)
    found = rules.allergens_in(row.ingredient_names)
    return RefRecipe(
        id=row.id,
        title=title,
        ingredient_lines=row.ingredient_lines,
        ingredient_names=row.ingredient_names,
        directions=row.directions,
        meal_types=tags,
        allergens=[key for key in rules.allergen_keys if key in found],
        vegetarian=flags["vegetarian"],
        vegan=flags["vegan"],
        gluten_free=flags["gluten_free"],
        dairy_free=flags["dairy_free"],
    )


# --- output ------------------------------------------------------------------

def _clean(text):
    return text.replace("\x00", "")  # Postgres text cannot hold NUL


def _array_literal(items):
    quoted = ('"' + _clean(item).replace("\\", "\\\\").replace('"', '\\"') + '"' for item in items)
    return "{" + ",".join(quoted) + "}"


def _bool(value):
    return "true" if value else "false"


def _open_csv(path):
    # errors="replace": a lone surrogate from json.loads becomes "?" instead of crashing the run at the end
    f = open(path, "w", encoding="utf-8", errors="replace", newline="")
    return f, csv.writer(f, lineterminator="\n")


def write_outputs(out_dir, recipes, stats, pairs):
    """Write the three CSVs. `stats` is a Counter of name -> recipe count; only counts >= 5 are written."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    f, writer = _open_csv(out_dir / "reference_recipes.csv")
    with f:
        writer.writerow(RECIPE_COLUMNS)
        for r in recipes:
            writer.writerow([
                r.id, _clean(r.title),
                _array_literal(r.ingredient_lines), _array_literal(r.ingredient_names),
                _array_literal(r.directions), _array_literal(r.meal_types),
                _array_literal(r.allergens),
                _bool(r.vegetarian), _bool(r.vegan), _bool(r.gluten_free), _bool(r.dairy_free),
            ])

    f, writer = _open_csv(out_dir / "ingredient_stats.csv")
    with f:
        writer.writerow(["name", "recipe_count"])
        for name in sorted(stats):
            if stats[name] >= MIN_STATS_COUNT:
                writer.writerow([name, stats[name]])

    f, writer = _open_csv(out_dir / "ingredient_pairings.csv")
    with f:
        writer.writerow(["ingredient", "partner", "together_count"])
        for ingredient in sorted(pairs):
            for partner, count in pairs[ingredient]:
                writer.writerow([ingredient, partner, count])


# --- command line ------------------------------------------------------------

def _with_progress(rows, label, extra=None):
    for i, row in enumerate(rows, start=1):
        if i % PROGRESS_EVERY == 0:
            print(f"  {label}: {i:,} rows" + (f", {extra()}" if extra else ""), flush=True)
        yield row


def check_not_mostly_skipped(read_stats):
    """Raise InputError if pass 1 skipped more than half of the rows, or found none."""
    skipped, total = read_stats["skipped"], read_stats["rows"] + read_stats["skipped"]
    if total == 0:
        raise InputError("The input file has no data rows.")
    if skipped * 2 > total:
        raise InputError(f"{skipped:,} of {total:,} rows were skipped as malformed (more than half), "
                         "so something is wrong with the input file. Nothing was written.")


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Prepare reference recipes and ingredient statistics from RecipeNLG.")
    p.add_argument("--input", type=Path, default=DEFAULT_INPUT, help="RecipeNLG full_dataset.csv")
    p.add_argument("--out", type=Path, default=DEFAULT_OUT, help="output directory")
    p.add_argument("--min-common", type=int, default=50,
                   help="an ingredient is 'common' (gets pairings) at this many recipes; raise it if memory runs short")
    p.add_argument("--per-meal", type=int, default=15000, help="recipes per meal bucket")
    p.add_argument("--untagged", type=int, default=5000, help="untagged recipes, before shortfall top-up")
    return p.parse_args(argv)


def main(argv=None):
    args = _parse_args(argv)
    if not args.input.is_file():
        print(f"Input file not found: {args.input}\nSee {README} for where to get RecipeNLG's full_dataset.csv.",
              file=sys.stderr)
        return 2
    try:
        return _run(args)
    except InputError as error:
        print(f"Error: {error}", file=sys.stderr)
        return 2


def _run(args):
    started = time.monotonic()
    rules = FoodRules.load()

    print("Pass 1/3: counting ingredients")
    read_stats = Counter()
    counts = count_ingredients(_with_progress(read_rows(args.input, read_stats), "pass 1"))
    check_not_mostly_skipped(read_stats)

    common = {name for name, count in counts.items() if count >= args.min_common}
    print(f"Pass 2/3: counting pairs among {len(common):,} common ingredients")
    pair_stats = Counter()
    pairs = count_pairs(
        _with_progress(read_rows(args.input, Counter()), "pass 2", lambda: f"{pair_stats['pairs']:,} distinct pairs"),
        common, stats=pair_stats)
    print(f"  pass 2: {pair_stats['pairs']:,} distinct pairs counted")

    print("Pass 3/3: selecting recipes")
    bucket_stats = Counter()
    recipes = select_recipes(_with_progress(read_rows(args.input, Counter()), "pass 3"),
                             rules, args.per_meal, args.untagged, SEED, stats=bucket_stats)

    write_outputs(args.out, recipes, counts, pairs)

    kept_stats = sum(1 for c in counts.values() if c >= MIN_STATS_COUNT)
    print(f"Rows read: {read_stats['rows']:,}, skipped (malformed): {read_stats['skipped']:,}")
    print("Recipes per bucket: " + ", ".join(f"{k}={bucket_stats[k]:,}" for k in (*MEALS, "untagged"))
          + f", total={len(recipes):,}")
    print(f"Ingredient stats: {kept_stats:,}; ingredients with pairings: {len(pairs):,} "
          f"({pair_stats['pairs']:,} distinct pairs counted)")
    print(f"Wrote {args.out} in {time.monotonic() - started:.1f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
