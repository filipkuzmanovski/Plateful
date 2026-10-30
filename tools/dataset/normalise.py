"""Shared ingredient-name normaliser.

This is the single definition of an "ingredient name" for the dataset tooling.
The Java backend has a twin implementation that must produce identical output;
both read the same test cases (normalise-cases.csv) and the same irregular
plurals (RecipeAppBackend/src/main/resources/ingredient-irregulars.csv).

Standard library only.
"""

import csv
import re
import unicodedata
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
IRREGULARS_FILE = REPO_ROOT / "RecipeAppBackend/src/main/resources/ingredient-irregulars.csv"
MAX_NAME_LENGTH = 60


def _load_irregulars():
    with open(IRREGULARS_FILE, encoding="utf-8-sig", newline="") as f:
        return {row["plural"]: row["singular"] for row in csv.DictReader(f)}


_IRREGULARS = _load_irregulars()


def _clean(text):
    decomposed = unicodedata.normalize("NFKD", text)
    stripped = "".join(c for c in decomposed if not unicodedata.combining(c))
    lowered = re.sub(r"\s", " ", stripped.lower().replace("-", " "))
    letters_only = re.sub(r"[^a-z ]", "", lowered)
    # A lone "s" is a possessive remnant (e.g. an acute accent standing in for an apostrophe); drop it.
    return " ".join(w for w in letters_only.split() if w != "s")


def _singularise(word):
    if word in _IRREGULARS:
        return _IRREGULARS[word]
    if word.endswith("ies"):
        return word[:-3] + "y"
    if word.endswith("oes"):
        return word[:-2]
    if word.endswith(("ches", "shes", "sses", "xes")):
        return word[:-2]
    if word.endswith("s") and not word.endswith(("ss", "us", "is")):
        return word[:-1]
    return word


def normalise(name):
    """Ingredient form: singularise the last word. None if empty or over 60 chars."""
    words = _clean(name).split()
    if not words:
        return None
    words[-1] = _singularise(words[-1])
    result = " ".join(words)
    if not result or len(result) > MAX_NAME_LENGTH:
        return None
    return result


def normalise_words(text):
    """Title form: singularise every word. Never None, no length cap."""
    return " ".join(_singularise(w) for w in _clean(text).split())
