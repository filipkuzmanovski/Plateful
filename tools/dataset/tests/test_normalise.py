import csv
from pathlib import Path

import pytest

from normalise import REPO_ROOT, normalise, normalise_words

CASES_FILE = Path(__file__).resolve().parent.parent / "normalise-cases.csv"


def load_cases():
    with open(CASES_FILE, encoding="utf-8", newline="") as f:
        return [(r["input"], r["expected"] or None) for r in csv.DictReader(f)]


@pytest.mark.parametrize("raw,expected", load_cases())
def test_normalise_cases(raw, expected):
    assert normalise(raw) == expected


def test_normalise_words_singularises_every_word():
    assert normalise_words("Fluffy Pancakes with Berries") == "fluffy pancake with berry"


def test_normalise_words_returns_empty_string_not_none():
    assert normalise_words("!!!") == ""


def test_normalise_words_has_no_length_cap():
    assert normalise_words("a" * 61) == "a" * 61


def test_repo_root_contains_the_shared_irregulars_file():
    assert (REPO_ROOT / "RecipeAppBackend/src/main/resources/ingredient-irregulars.csv").is_file()


def test_normalise_words_drops_lone_s_without_double_space():
    assert normalise_words("a s b") == "a b"
