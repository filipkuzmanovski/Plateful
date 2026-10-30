"""Shared allergen, diet and meal rules.

Reads RecipeAppBackend/src/main/resources/food-rules.csv, the single source of
truth for both Python and (later) Java. Columns: kind,key,label,terms,exceptions.
Fields hold no commas or quotes; list items are separated by "|". A term written
"@key" imports that row's terms and exceptions.

Matching: a term matches a name when " " + name + " " contains " " + term + " ".
A name that matches any of a row's exceptions is skipped for that row.

Standard library only.
"""

from normalise import REPO_ROOT, normalise, normalise_words

RULES_FILE = REPO_ROOT / "RecipeAppBackend/src/main/resources/food-rules.csv"
HEADER = ["kind", "key", "label", "terms", "exceptions"]
KINDS = ("allergen", "diet", "meal")


def _split_items(field):
    return [item.strip() for item in field.split("|") if item.strip()]


def _matches(padded, phrases):
    return any(f" {p} " in padded for p in phrases)


class _Row:
    def __init__(self, kind, key, label, raw_terms, raw_exceptions):
        self.kind = kind
        self.key = key
        self.label = label
        self.raw_terms = raw_terms
        self.raw_exceptions = raw_exceptions
        self.terms = frozenset()
        self.exceptions = frozenset()

    def hits(self, padded):
        """True when the space-padded text matches a term and no exception."""
        if _matches(padded, self.exceptions):
            return False
        return _matches(padded, self.terms)


class FoodRules:
    def __init__(self, rows):
        self._rows = rows
        self._by_key = {row.key: row for row in rows}
        self._resolve_all()

    @classmethod
    def load(cls, path=None):
        path = path or RULES_FILE
        with open(path, encoding="utf-8", newline="") as f:
            lines = f.read().splitlines()
        if not lines or lines[0].split(",") != HEADER:
            raise ValueError(f"{path}: expected header {','.join(HEADER)}")
        rows, seen = [], set()
        for number, line in enumerate(lines[1:], start=2):
            if not line.strip():
                continue
            fields = line.split(",", 4)
            if len(fields) != 5:
                raise ValueError(f"{path}:{number}: expected 5 fields, got {len(fields)}")
            kind, key, label, terms, exceptions = (x.strip() for x in fields)
            if kind not in KINDS:
                raise ValueError(f"{path}:{number}: unknown kind {kind!r}")
            if not key or key in seen:
                raise ValueError(f"{path}:{number}: missing or duplicate key {key!r}")
            seen.add(key)
            rows.append(_Row(kind, key, label, _split_items(terms), _split_items(exceptions)))
        return cls(rows)

    def _resolve_all(self):
        cache = {}
        for row in self._rows:
            terms, exceptions = self._resolve(row.key, (), cache)
            row.terms, row.exceptions = frozenset(terms), frozenset(exceptions)

    def _resolve(self, key, stack, cache):
        if key in cache:
            return cache[key]
        if key in stack:
            raise ValueError("reference cycle: " + " -> ".join(stack + (key,)))
        row = self._by_key[key]
        terms, exceptions = set(), set()
        for item in row.raw_terms:
            if item.startswith("@"):
                ref = item[1:]
                if ref not in self._by_key:
                    raise ValueError(f"{key}: unknown reference {item!r}")
                ref_terms, ref_exceptions = self._resolve(ref, stack + (key,), cache)
                terms |= ref_terms
                exceptions |= ref_exceptions
            else:
                terms.add(self._normalised(key, item))
        for item in row.raw_exceptions:
            if item.startswith("@"):
                raise ValueError(f"{key}: references are not allowed in exceptions: {item!r}")
            exceptions.add(self._normalised(key, item))
        cache[key] = (terms, exceptions)
        return cache[key]

    @staticmethod
    def _normalised(key, item):
        value = normalise(item)
        if value is None:
            raise ValueError(f"{key}: {item!r} normalises to nothing")
        return value

    @property
    def allergen_keys(self):
        return [row.key for row in self._rows if row.kind == "allergen"]

    @staticmethod
    def _padded_names(names):
        normalised = {normalise(n) for n in names}
        normalised.discard(None)
        return [f" {n} " for n in normalised]

    def allergens_in(self, names):
        padded = self._padded_names(names)
        return {
            row.key for row in self._rows
            if row.kind == "allergen" and any(row.hits(p) for p in padded)
        }

    def diet_flags(self, names):
        padded = self._padded_names(names)
        return {
            row.key: not any(row.hits(p) for p in padded)
            for row in self._rows if row.kind == "diet"
        }

    def meal_types(self, title):
        padded = f" {normalise_words(title)} "
        return [row.key for row in self._rows if row.kind == "meal" and row.hits(padded)]
