import pytest

from rules import RULES_FILE, FoodRules
HEADER = "kind,key,label,terms,exceptions\n"


@pytest.fixture(scope="module")
def rules():
    return FoodRules.load()


def write_rules(tmp_path, body):
    path = tmp_path / "rules.csv"
    path.write_text(HEADER + body, encoding="utf-8")
    return path


# --- the shared file itself -------------------------------------------------

def test_allergen_keys_are_the_fourteen_in_order(rules):
    assert rules.allergen_keys == [
        "gluten", "crustaceans", "eggs", "fish", "peanuts", "soy", "milk",
        "tree_nuts", "celery", "mustard", "sesame", "sulphites", "lupin", "molluscs",
    ]


def test_shared_file_is_plain_split_friendly():
    raw = RULES_FILE.read_bytes()
    assert not raw.startswith(b"\xef\xbb\xbf"), "no BOM"
    lines = raw.decode("utf-8").splitlines()
    assert lines[0] == "kind,key,label,terms,exceptions"
    for line in lines[1:]:
        assert '"' not in line
        assert len(line.split(",", 4)) == 5, line
        assert line.count(",") == 4, line


def test_shared_file_has_expected_row_counts():
    kinds = [line.split(",")[0] for line in RULES_FILE.read_text(encoding="utf-8").splitlines()[1:]]
    assert kinds.count("allergen") == 14
    assert kinds.count("diet") == 4
    assert kinds.count("meal") == 3


# --- allergens --------------------------------------------------------------

def test_butter_is_milk(rules):
    assert rules.allergens_in(["unsalted butter"]) == {"milk"}


def test_whole_word_match_only(rules):
    assert rules.allergens_in(["butternut squash"]) == set()


def test_peanut_butter_is_peanuts_not_milk(rules):
    assert rules.allergens_in(["peanut butter"]) == {"peanuts"}


def test_coconut_milk_is_not_milk(rules):
    assert rules.allergens_in(["coconut milk"]) == set()


def test_plural_input_is_normalised(rules):
    assert rules.allergens_in(["eggs"]) == {"eggs"}


def test_names_are_normalised_before_matching(rules):
    assert rules.allergens_in(["Unsalted  Butter"]) == {"milk"}
    assert rules.allergens_in(["Crème Fraîche"]) == {"milk"}


def test_unnormalisable_names_are_ignored(rules):
    assert rules.allergens_in(["", "123", "x" * 80]) == set()


def test_union_across_names(rules):
    got = rules.allergens_in(["wheat flour", "shrimp", "soy sauce", "almonds", "red wine"])
    assert got == {"gluten", "crustaceans", "soy", "tree_nuts", "sulphites"}


@pytest.mark.parametrize("name,key", [
    ("spaghetti", "gluten"), ("breadcrumbs", "gluten"), ("flour tortillas", "gluten"),
    ("couscous", "gluten"), ("barley", "gluten"), ("seitan", "gluten"),
    ("mayonnaise", "eggs"), ("meringue", "eggs"),
    ("salmon fillet", "fish"), ("fish sauce", "fish"), ("anchovies", "fish"),
    ("tiger prawns", "crustaceans"), ("lobster tail", "crustaceans"),
    ("mussels", "molluscs"), ("calamari", "molluscs"), ("oyster sauce", "molluscs"),
    ("pine nuts", "tree_nuts"), ("brazil nuts", "tree_nuts"), ("hazelnuts", "tree_nuts"),
    ("tofu", "soy"), ("miso paste", "soy"), ("edamame", "soy"),
    ("celery stalks", "celery"), ("dijon mustard", "mustard"), ("tahini", "sesame"),
    ("dried apricots", "sulphites"), ("lupin flour", "lupin"), ("parmesan", "milk"),
    ("pizza dough", "gluten"), ("pie crust", "gluten"), ("naan", "gluten"), ("flatbread", "gluten"),
    ("cornbread", "gluten"), ("pancake mix", "gluten"), ("corn tortillas", "gluten"), ("chapatis", "gluten"),
    ("sourdough", "gluten"), ("gingerbread", "gluten"), ("wonton wrappers", "gluten"),
    ("graham crackers", "gluten"), ("durum", "gluten"), ("matzo meal", "gluten"),
    ("provolone", "milk"), ("gorgonzola", "milk"), ("parmigiano reggiano", "milk"),
    ("queso fresco", "milk"), ("camembert", "milk"), ("burrata", "milk"), ("quark", "milk"),
    ("goat cheese", "milk"), ("goat milk", "milk"), ("gelato", "milk"),
    ("mollusks", "molluscs"), ("bearnaise sauce", "eggs"),
    ("dashi", "fish"), ("smoked lox", "fish"), ("gravlax", "fish"), ("mullet", "fish"),
])
def test_generous_terms(rules, name, key):
    assert key in rules.allergens_in([name])


@pytest.mark.parametrize("name,expected", [
    ("oyster mushrooms", set()), ("crab apples", set()), ("cream of tartar", set()),
    ("butter beans", set()), ("eggplant", set()), ("gluten free flour", set()),
    ("rice flour", set()), ("almond milk", {"tree_nuts"}),
])
def test_known_safe_lookalikes(rules, name, expected):
    assert rules.allergens_in([name]) == expected


# --- diets ------------------------------------------------------------------

def test_chicken_is_not_vegetarian(rules):
    assert rules.diet_flags(["chicken breast", "rice"])["vegetarian"] is False


def test_honey_is_vegetarian_but_not_vegan(rules):
    flags = rules.diet_flags(["honey", "oat"])
    assert flags["vegetarian"] is True
    assert flags["vegan"] is False


def test_coconut_milk_is_dairy_free_through_the_import(rules):
    assert rules.diet_flags(["coconut milk"])["dairy_free"] is True


def test_diet_keys_and_gluten_dairy_flags(rules):
    flags = rules.diet_flags(["butter", "flour"])
    assert set(flags) == {"vegetarian", "vegan", "gluten_free", "dairy_free"}
    assert flags == {"vegetarian": True, "vegan": False, "gluten_free": False, "dairy_free": False}


def test_plain_vegetables_fit_every_diet(rules):
    assert rules.diet_flags(["carrot", "olive oil", "rice"]) == {
        "vegetarian": True, "vegan": True, "gluten_free": True, "dairy_free": True,
    }


def test_seafood_and_gelatin_are_not_vegetarian(rules):
    for name in ["shrimp", "salmon", "gelatin", "bacon", "oyster sauce", "hot dog", "andouille",
                 "pastrami", "gammon", "pigeon", "squab", "goat", "ground goat", "goat shank",
                 "goat ribs", "goat curry", "diced goat", "boneless goat"]:
        assert rules.diet_flags([name])["vegetarian"] is False, name


def test_vegetarian_lookalikes_pass(rules):
    for name in ["oyster mushrooms", "kidney beans", "lamb's lettuce", "goat cheese", "goat milk",
                 "pigeon peas"]:
        assert rules.diet_flags([name])["vegetarian"] is True, name


def test_goat_cheese_is_vegetarian_but_neither_vegan_nor_dairy_free(rules):
    flags = rules.diet_flags(["goat cheese"])
    assert flags["vegetarian"] is True
    assert flags["vegan"] is False
    assert flags["dairy_free"] is False


def test_empty_ingredient_list_fits_all_diets(rules):
    assert all(rules.diet_flags([]).values())


# --- meals ------------------------------------------------------------------

def test_pancakes_are_breakfast(rules):
    assert rules.meal_types("Fluffy Pancakes") == ["breakfast"]


def test_soup_is_lunch(rules):
    assert rules.meal_types("Chicken Soup") == ["lunch"]


def test_unknown_title_has_no_meal(rules):
    assert rules.meal_types("Mystery Dish") == []


def test_title_may_match_several_meals_in_file_order(rules):
    assert rules.meal_types("Pasta Salad") == ["lunch", "dinner"]


def test_multi_word_meal_term(rules):
    assert rules.meal_types("Classic French Toast") == ["breakfast"]


def test_title_words_are_all_singularised(rules):
    assert rules.meal_types("Sweet Potato Pies") == ["dinner"]


# --- loading ----------------------------------------------------------------

def test_unknown_reference_raises(tmp_path):
    path = write_rules(tmp_path, "diet,vegan,Vegan,@nope|honey,\n")
    with pytest.raises(ValueError):
        FoodRules.load(path)


def test_duplicate_key_raises(tmp_path):
    path = write_rules(tmp_path, "allergen,eggs,Eggs,egg,\nallergen,eggs,Eggs again,mayonnaise,\n")
    with pytest.raises(ValueError):
        FoodRules.load(path)


def test_reference_cycle_raises(tmp_path):
    path = write_rules(tmp_path, "diet,a,A,@b,\ndiet,b,B,@a,\n")
    with pytest.raises(ValueError):
        FoodRules.load(path)


def test_term_that_normalises_to_nothing_raises(tmp_path):
    path = write_rules(tmp_path, "allergen,eggs,Eggs,egg|123,\n")
    with pytest.raises(ValueError):
        FoodRules.load(path)


def test_wrong_field_count_raises(tmp_path):
    path = write_rules(tmp_path, "allergen,eggs,Eggs,egg\n")
    with pytest.raises(ValueError):
        FoodRules.load(path)


def test_reference_imports_terms_and_exceptions(tmp_path):
    path = write_rules(
        tmp_path,
        "allergen,milk,Milk,milk|butter,peanut butter\n"
        "diet,dairy_free,Dairy-free,@milk|whey,\n",
    )
    loaded = FoodRules.load(path)
    assert loaded.diet_flags(["butter"])["dairy_free"] is False
    assert loaded.diet_flags(["whey protein"])["dairy_free"] is False
    assert loaded.diet_flags(["peanut butter"])["dairy_free"] is True


def test_forward_reference_is_fine(tmp_path):
    path = write_rules(tmp_path, "diet,d,D,@a,\nallergen,a,A,egg,\n")
    assert FoodRules.load(path).diet_flags(["egg"])["d"] is False


def test_exceptions_only_mask_their_own_rows_terms(rules):
    flags = rules.diet_flags(["honey roasted peanut butter"])
    assert flags["vegan"] is False
    assert flags["vegetarian"] is True
    assert flags["dairy_free"] is True


def test_coconut_milk_is_vegan(rules):
    assert rules.diet_flags(["coconut milk"])["vegan"] is True


def test_imported_exception_does_not_hide_the_importing_rows_own_terms(tmp_path):
    path = write_rules(
        tmp_path,
        "allergen,milk,Milk,milk|butter,peanut butter\n"
        "diet,vegan,Vegan,@milk|honey,\n",
    )
    loaded = FoodRules.load(path)
    assert loaded.diet_flags(["peanut butter"])["vegan"] is True
    assert loaded.diet_flags(["peanut butter honey"])["vegan"] is False
