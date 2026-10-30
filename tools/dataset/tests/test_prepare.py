import csv
import re
from collections import Counter
from pathlib import Path

import pytest

import prepare
from prepare import (
    ParsedRow,
    RefRecipe,
    count_ingredients,
    count_pairs,
    read_rows,
    select_recipes,
    write_audit,
    write_outputs,
)
from normalise import normalise
from rules import FoodRules

FIXTURE = Path(__file__).parent / "fixtures" / "mini_dataset.csv"


@pytest.fixture(scope="module")
def rules():
    return FoodRules.load()


@pytest.fixture()
def fixture_rows():
    stats = Counter()
    return list(read_rows(FIXTURE, stats)), stats


def parsed(id_, names, title="t", lines=None, directions=None):
    lines = lines if lines is not None else [f"1 {n}" for n in names]
    directions = directions if directions is not None else ["Mix.", "Cook."]
    return ParsedRow(id_, title, lines, directions, list(names))


# --- read_rows ---------------------------------------------------------------

def test_read_rows_skips_the_malformed_row_and_counts_it(fixture_rows):
    rows, stats = fixture_rows
    assert stats["skipped"] == 1
    assert len(rows) == 19
    assert 0 not in [r.id for r in rows]


def test_read_rows_yields_empty_ner_rows(fixture_rows):
    rows, _ = fixture_rows
    empty = [r for r in rows if r.id == 1]
    assert len(empty) == 1 and empty[0].ingredient_names == []


def test_read_rows_normalises_and_deduplicates_names(fixture_rows):
    rows, _ = fixture_rows
    by_id = {r.id: r for r in rows}
    assert by_id[11].ingredient_names == ["tortilla", "egg", "cheese"]
    assert by_id[2].ingredient_lines[0] == "1 cup 2 bananas"


def test_read_rows_skips_non_int_ids_and_non_string_lists(tmp_path):
    path = tmp_path / "bad.csv"
    path.write_text(
        ',title,ingredients,directions,link,source,NER\n'
        'x,A,"[""1 egg""]","[""Cook.""]",l,s,"[""egg""]"\n'
        '1,B,"[1, 2]","[""Cook.""]",l,s,"[""egg""]"\n'
        '2,C,"{}","[""Cook.""]",l,s,"[""egg""]"\n'
        '3,D,"[""1 egg""]","[""Cook.""]",l,s,"[""egg"", ""EGGS""]"\n',
        encoding="utf-8",
    )
    stats = Counter()
    rows = list(read_rows(path, stats))
    assert [r.id for r in rows] == [3]
    assert rows[0].ingredient_names == ["egg"]
    assert stats["skipped"] == 3


def test_read_rows_caps_the_field_size_at_10_mb(fixture_rows):
    assert prepare.MAX_FIELD_SIZE == 10 * 1024 * 1024
    assert csv.field_size_limit() == prepare.MAX_FIELD_SIZE  # set by the fixture's read_rows run


def test_an_unbalanced_quote_costs_one_skipped_row_not_the_rest_of_the_file(tmp_path, monkeypatch):
    monkeypatch.setattr(prepare, "MAX_FIELD_SIZE", 2000)
    good = '{n},Ok {n},"[""1 egg"", ""2 egg"", ""3 egg""]","[""Cook."", ""Serve.""]",l,s,"[""egg""]"\n'
    path = tmp_path / "quote.csv"
    path.write_text(
        ',title,ingredients,directions,link,source,NER\n'
        + good.format(n=1)
        + '2,"Stray quote,[x],[y],l,s,[]\n'
        + "".join(good.format(n=n) for n in range(3, 400)),
        encoding="utf-8",
    )
    stats = Counter()
    rows = list(read_rows(path, stats))
    assert stats["skipped"] >= 1
    assert [r.id for r in rows][0] == 1
    assert 2 not in [r.id for r in rows]
    assert len(rows) > 300  # the rows after the broken one are still read


def test_read_rows_accepts_a_utf8_bom(tmp_path):
    path = tmp_path / "bom.csv"
    path.write_text(
        ',title,ingredients,directions,link,source,NER\n'
        '5,Ok,"[""1 egg""]","[""Cook.""]",l,s,"[""egg""]"\n',
        encoding="utf-8-sig",
    )
    rows = list(read_rows(path, Counter()))
    assert [r.id for r in rows] == [5]


def test_read_rows_rejects_a_file_with_the_wrong_header(tmp_path):
    path = tmp_path / "wrong.csv"
    path.write_text('id,name,body\n1,a,b\n', encoding="utf-8")
    with pytest.raises(prepare.InputError) as error:
        list(read_rows(path, Counter()))
    message = str(error.value)
    assert "title" in message and "NER" in message


def test_read_rows_rejects_an_empty_file(tmp_path):
    path = tmp_path / "empty.csv"
    path.write_text("", encoding="utf-8")
    with pytest.raises(prepare.InputError):
        list(read_rows(path, Counter()))


# --- count_ingredients -------------------------------------------------------

def test_count_ingredients_counts_recipes_per_name(fixture_rows):
    rows, _ = fixture_rows
    counts = count_ingredients(rows)
    # rows 2, 3, 7, 11 (eggs), 16, 19; the malformed row 0 is not counted
    assert counts["egg"] == 6
    assert counts["butter"] == 5


# --- count_pairs -------------------------------------------------------------

def test_count_pairs_keeps_top_n_partners_with_ties_by_name():
    rows = [
        parsed(1, ["a", "b", "c", "d"]),
        parsed(2, ["a", "b", "c", "d"]),
        parsed(3, ["a", "b", "c", "d"]),
        parsed(4, ["a", "e"]),
        parsed(5, ["a", "e"]),
        parsed(6, ["a", "f"]),
        parsed(7, ["a", "zzz"]),  # zzz is not common: ignored
    ]
    pairs = count_pairs(rows, {"a", "b", "c", "d", "e", "f"}, top_n=2)
    assert pairs["a"] == [("b", 3), ("c", 3)]
    assert pairs["e"] == [("a", 2)]
    assert pairs["b"] == [("a", 3), ("c", 3)]
    assert "zzz" not in pairs


def test_count_pairs_orders_by_count_then_name():
    rows = [parsed(1, ["a", "z"]), parsed(2, ["a", "z"]), parsed(3, ["a", "m"]), parsed(4, ["a", "b"])]
    pairs = count_pairs(rows, {"a", "b", "m", "z"})
    assert pairs["a"] == [("z", 2), ("b", 1), ("m", 1)]


def test_count_pairs_reports_the_distinct_pair_count():
    rows = [parsed(1, ["a", "b", "c"]), parsed(2, ["a", "b"]), parsed(3, ["c", "d"])]
    stats = Counter()
    count_pairs(rows, {"a", "b", "c", "d"}, stats=stats)
    assert stats["pairs"] == 4  # a-b, a-c, b-c, c-d


# --- select_recipes ----------------------------------------------------------

def ids(recipes):
    return [r.id for r in recipes]


def test_select_is_deterministic_for_a_seed(fixture_rows, rules):
    rows, _ = fixture_rows
    first = select_recipes(rows, rules, per_meal=2, untagged=2, seed=7)
    second = select_recipes(rows, rules, per_meal=2, untagged=2, seed=7)
    assert ids(first) == ids(second)
    assert all(isinstance(r, RefRecipe) for r in first)


def test_select_applies_the_quality_filters(fixture_rows, rules):
    rows, _ = fixture_rows
    chosen = select_recipes(rows, rules, per_meal=100, untagged=100, seed=1)
    chosen_ids = set(ids(chosen))
    assert all(len(r.ingredient_lines) >= 3 for r in chosen)
    assert all(r.ingredient_names for r in chosen)
    assert 1 not in chosen_ids   # empty NER
    assert 4 not in chosen_ids   # two ingredient lines
    assert 18 not in chosen_ids  # a single direction
    assert len(chosen) == 15


def test_select_keeps_a_duplicate_normalised_title_once(fixture_rows, rules):
    rows, _ = fixture_rows
    chosen = select_recipes(rows, rules, per_meal=100, untagged=100, seed=1)
    assert [i for i in ids(chosen) if i in (2, 3)] == [2]


def test_select_filters_title_and_direction_limits(rules):
    long_title = "x" * 256
    rows = [
        parsed(1, ["a"], title=long_title, lines=["1", "2", "3"]),
        parsed(2, ["a"], title="   ", lines=["1", "2", "3"]),
        parsed(3, ["a"], title="Ok", lines=["1", "2", "3"], directions=["x" * 2001, "fine"]),
        parsed(4, ["a"], title="Many Steps", lines=["1", "2", "3"], directions=["ok"] * 26),
        parsed(5, ["a"], title="Many Lines", lines=["1"] * 21, directions=["ok", "ok"]),
        parsed(6, ["a"], title="Fine", lines=["1", "2", "3"], directions=["x" * 2000, "ok"]),
    ]
    assert ids(select_recipes(rows, rules, per_meal=10, untagged=10, seed=1)) == [6]


def test_select_fills_a_short_bucket_from_untagged(fixture_rows, rules):
    rows, _ = fixture_rows
    # 8 meal-tagged recipes exist (3 breakfast, 3 lunch, 3 dinner, one shared), 7 untagged.
    stats = Counter()
    chosen = select_recipes(rows, rules, per_meal=4, untagged=1, seed=3, stats=stats)
    tagged = [r for r in chosen if r.meal_types]
    plain = [r for r in chosen if not r.meal_types]
    assert len(tagged) == 8
    # shortfall is 3 * 4 - 8 = 4, so 1 + 4 untagged recipes are taken
    assert len(plain) == 5
    assert len(set(ids(chosen))) == len(chosen)
    assert stats["untagged"] == 5


def test_select_gives_a_multi_tag_recipe_to_the_first_bucket_with_room(fixture_rows, rules):
    rows, _ = fixture_rows
    chosen = select_recipes(rows, rules, per_meal=1, untagged=0, seed=5)
    assert len(chosen) == 3
    assert len(set(ids(chosen))) == 3
    for r in chosen:
        assert r.meal_types


def test_select_caps_each_bucket(fixture_rows, rules):
    rows, _ = fixture_rows
    chosen = select_recipes(rows, rules, per_meal=1, untagged=2, seed=9)
    assert len([r for r in chosen if not r.meal_types]) == 2
    assert len(chosen) == 5


def test_select_matches_an_exhaustive_reference_selection(rules):
    import random
    rng = random.Random(1)
    stems = ["Pancake", "Soup", "Stew", "Burrito Breakfast", "Taco Pie", "Cake", "Salad Roast", "Pasta"]
    letters = "abcdefghijklmnopqrstuvwxyz"
    rows = [
        parsed(i, ["egg"], title=f"{rng.choice(stems)} {letters[i % 26]}{letters[i // 26 % 26]}{letters[i // 676]}",
               lines=["1", "2", "3"])
        for i in range(2000)
    ]
    per_meal, untagged = 60, 25
    keys = random.Random(5)
    keyed = [(keys.random(), row) for row in rows]  # one key per kept row, in file order
    tags = {row.id: rules.meal_types(row.title) for row in rows}
    expected, taken = [], set()
    for meal in ("breakfast", "lunch", "dinner"):
        pool = sorted((k, r.id) for k, r in keyed if meal in tags[r.id] and r.id not in taken)[:per_meal]
        taken.update(i for _, i in pool)
        expected += [i for _, i in pool]
    shortfall = per_meal * 3 - len(expected)
    plain = sorted((k, r.id) for k, r in keyed if not tags[r.id])[: untagged + shortfall]
    expected += [i for _, i in plain]
    assert ids(select_recipes(rows, rules, per_meal, untagged, seed=5)) == sorted(expected)


def test_select_computes_tags_allergens_and_flags(fixture_rows, rules):
    rows, _ = fixture_rows
    chosen = {r.id: r for r in select_recipes(rows, rules, per_meal=100, untagged=100, seed=1)}
    burrito = chosen[11]
    assert burrito.meal_types == ["breakfast", "lunch"]
    assert "eggs" in burrito.allergens and "milk" in burrito.allergens
    assert burrito.allergens == sorted(burrito.allergens, key=rules.allergen_keys.index)
    assert burrito.vegetarian is True and burrito.vegan is False
    assert chosen[8].vegetarian is False  # beef stew
    assert chosen[15].vegan is True       # plain rice


def only(rules, row):
    chosen = select_recipes([row], rules, per_meal=5, untagged=5, seed=1)
    assert len(chosen) == 1
    return chosen[0]


def test_tags_come_from_the_lines_too_pecans_halves(rules):
    name = normalise("chopped pecans halves")  # "chopped pecans halve": the NER form misses "pecan"
    assert "tree_nuts" not in rules.allergens_in([name])
    row = parsed(1, [name, "sugar"], title="Nut Bars",
                 lines=["1 c. chopped pecans halves", "1 c. sugar", "2 c. water"])
    assert "tree_nuts" in only(rules, row).allergens


def test_tags_come_from_the_lines_too_anchovies_fillets(rules):
    name = normalise("anchovies fillets")
    assert "fish" not in rules.allergens_in([name])
    row = parsed(1, [name, "garlic"], title="Bagna Cauda",
                 lines=["4 anchovies fillets, mashed", "2 cloves garlic", "1 c. olive oil"])
    recipe = only(rules, row)
    assert "fish" in recipe.allergens
    assert recipe.vegetarian is False


def test_an_item_missing_from_the_ner_is_tagged_from_its_line(rules):
    row = parsed(1, ["flour", "sugar"], title="Plain Cake Mix",
                 lines=["2 c. flour", "1 c. sugar", "2 eggs, beaten"])
    recipe = only(rules, row)
    assert "eggs" in recipe.allergens
    assert recipe.vegan is False
    assert recipe.ingredient_names == ["flour", "sugar"]  # the stored names are still the NER names


def test_lines_are_matched_without_the_name_length_cap(rules):
    line = "1 c. of the very best extra finely and freshly chopped toasted walnuts"
    assert normalise(line) is None
    row = parsed(1, ["sugar"], title="Long Line", lines=[line, "1 c. sugar", "1 c. water"])
    assert "tree_nuts" in only(rules, row).allergens


def test_line_tagging_runs_only_for_recipes_admitted_to_a_pool(rules, monkeypatch):
    calls = []
    original = prepare._tag_names
    monkeypatch.setattr(prepare, "_tag_names", lambda row: calls.append(row.id) or original(row))
    rows = [parsed(i, ["rice"], title=f"Rice {chr(97 + i % 26)}{chr(97 + i // 26)}", lines=["1", "2", "3"]) for i in range(500)]
    chosen = select_recipes(rows, rules, per_meal=1, untagged=2, seed=4)
    assert len(chosen) == 5
    assert len(calls) < 100  # a full scan would tag all 500


# --- write_audit -------------------------------------------------------------

def test_write_audit_lists_the_most_frequent_unflagged_names(tmp_path, rules):
    counts = Counter({"salt": 90, "butter": 80, "sugar": 70, "chicken": 60, "water": 70, "onion": 10})
    path = write_audit(tmp_path, counts, rules, limit=3)
    assert path == tmp_path / "rules_audit.txt"
    assert path.read_text(encoding="utf-8").splitlines() == ["salt\t90", "sugar\t70", "water\t70"]


def test_write_audit_defaults_to_1500_names(tmp_path, rules):
    assert prepare.AUDIT_SIZE == 1500
    counts = Counter({f"plainname {chr(97 + i % 26)}{chr(97 + i // 26 % 26)}{chr(97 + i // 676)}": 2000 - i
                      for i in range(1600)})
    lines = write_audit(tmp_path, counts, rules).read_text(encoding="utf-8").splitlines()
    assert len(lines) == 1500
    assert lines[0].endswith("\t2000")


# --- write_outputs -----------------------------------------------------------

def unarray(text):
    assert text.startswith("{") and text.endswith("}")
    items = re.findall(r'"((?:[^"\\]|\\.)*)"', text)
    return [re.sub(r"\\(.)", r"\1", item) for item in items]


def read_csv(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.reader(f))


def test_write_outputs_round_trips_escaping(tmp_path):
    recipe = RefRecipe(
        id=42,
        title='She said "hi", then left',
        ingredient_lines=['1 "big" egg, beaten', r"2 tbsp a\b"],
        ingredient_names=["egg", "a b"],
        directions=['Say "stop",\nthen go.'],
        meal_types=["dinner"],
        allergens=[],
        vegetarian=True,
        vegan=False,
        gluten_free=True,
        dairy_free=True,
    )
    write_outputs(tmp_path, [recipe], Counter({"egg": 9, "rare": 4}), {"egg": [("a b", 3)]})

    rows = read_csv(tmp_path / "reference_recipes.csv")
    assert rows[0] == [
        "id", "title", "ingredient_lines", "ingredient_names", "directions",
        "meal_types", "allergens", "vegetarian", "vegan", "gluten_free", "dairy_free",
    ]
    row = rows[1]
    assert row[0] == "42"
    assert row[1] == 'She said "hi", then left'
    assert unarray(row[2]) == recipe.ingredient_lines
    assert unarray(row[3]) == recipe.ingredient_names
    assert unarray(row[4]) == recipe.directions
    assert row[5] == '{"dinner"}'
    assert row[6] == "{}"
    assert row[7:] == ["true", "false", "true", "true"]


def test_write_outputs_stats_and_pairings(tmp_path):
    write_outputs(
        tmp_path, [],
        Counter({"egg": 9, "salt": 5, "rare": 4}),
        {"egg": [("salt", 3), ("flour", 2)], "salt": [("egg", 3)]},
    )
    assert read_csv(tmp_path / "ingredient_stats.csv") == [
        ["name", "recipe_count"], ["egg", "9"], ["salt", "5"],
    ]
    assert read_csv(tmp_path / "ingredient_pairings.csv") == [
        ["ingredient", "partner", "together_count"],
        ["egg", "salt", "3"], ["egg", "flour", "2"], ["salt", "egg", "3"],
    ]
    raw = (tmp_path / "ingredient_stats.csv").read_bytes()
    assert b"\r" not in raw


def test_write_outputs_creates_the_output_directory(tmp_path):
    target = tmp_path / "nested" / "out"
    write_outputs(target, [], Counter(), {})
    assert (target / "reference_recipes.csv").exists()


def test_write_outputs_survives_a_lone_surrogate(tmp_path):
    recipe = RefRecipe(
        id=1, title="Bad \ud800 title", ingredient_lines=["1 egg\udfff"], ingredient_names=["egg"],
        directions=["Cook."], meal_types=[], allergens=[],
        vegetarian=True, vegan=True, gluten_free=True, dairy_free=True,
    )
    write_outputs(tmp_path, [recipe], Counter({"egg": 9}), {})
    row = read_csv(tmp_path / "reference_recipes.csv")[1]
    assert row[1] == "Bad ? title"
    assert unarray(row[2]) == ["1 egg?"]


# --- CLI ---------------------------------------------------------------------

def test_cli_reports_a_missing_input_file(tmp_path, capsys):
    code = prepare.main(["--input", str(tmp_path / "nope.csv"), "--out", str(tmp_path / "out")])
    assert code == 2
    assert "README.md" in capsys.readouterr().err


def test_cli_end_to_end_on_the_fixture(tmp_path, capsys):
    out = tmp_path / "out"
    code = prepare.main([
        "--input", str(FIXTURE), "--out", str(out),
        "--min-common", "2", "--per-meal", "2", "--untagged", "2",
    ])
    assert code == 0
    recipes = read_csv(out / "reference_recipes.csv")
    assert len(recipes) - 1 == 8
    assert (out / "ingredient_stats.csv").exists()
    pairings = read_csv(out / "ingredient_pairings.csv")
    assert ["egg", "flour", "2"] in pairings
    out_text = capsys.readouterr().out
    assert "skipped" in out_text
    assert str(out / "rules_audit.txt") in out_text


def test_cli_writes_the_rules_audit_on_the_fixture(tmp_path, rules):
    out = tmp_path / "out"
    prepare.main(["--input", str(FIXTURE), "--out", str(out),
                  "--min-common", "2", "--per-meal", "2", "--untagged", "2"])
    lines = (out / "rules_audit.txt").read_text(encoding="utf-8").splitlines()
    entries = [line.split("\t") for line in lines]
    assert all(len(e) == 2 for e in entries)
    names = [name for name, _ in entries]
    numbers = [int(count) for _, count in entries]
    assert numbers == sorted(numbers, reverse=True)
    assert "sugar" in names and "rice" in names
    assert "egg" not in names and "butter" not in names and "chicken" not in names and "honey" not in names
    for name in names:
        assert rules.allergens_in([name]) == set() and all(rules.diet_flags([name]).values()), name
    sugar = dict(entries)["sugar"]
    assert sugar == "3"  # rows 12, 13 and 16


def test_cli_rejects_a_file_with_the_wrong_header(tmp_path, capsys):
    path = tmp_path / "wrong.csv"
    path.write_text("id,name\n1,a\n", encoding="utf-8")
    code = prepare.main(["--input", str(path), "--out", str(tmp_path / "out")])
    assert code != 0
    assert "NER" in capsys.readouterr().err
    assert not (tmp_path / "out").exists()


def test_cli_stops_when_more_than_half_the_rows_are_skipped(tmp_path, capsys):
    path = tmp_path / "mostly_bad.csv"
    good = '1,Ok,"[""1 egg""]","[""Cook.""]",l,s,"[""egg""]"\n'
    bad = "{n},Bad,[not json,[x],l,s,[]\n"
    path.write_text(
        ",title,ingredients,directions,link,source,NER\n" + good + "".join(bad.format(n=n) for n in range(2, 5)),
        encoding="utf-8",
    )
    code = prepare.main(["--input", str(path), "--out", str(tmp_path / "out")])
    assert code != 0
    assert "skipped" in capsys.readouterr().err
    assert not (tmp_path / "out").exists()


def test_cli_prints_the_distinct_pair_count(tmp_path, capsys):
    prepare.main(["--input", str(FIXTURE), "--out", str(tmp_path / "out"),
                  "--min-common", "2", "--per-meal", "2", "--untagged", "2"])
    assert re.search(r"[\d,]+ distinct pairs", capsys.readouterr().out)
