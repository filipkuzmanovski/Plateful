"""Load the CSVs written by prepare.py into the Supabase Postgres database (insert-only).

    python tools/dataset/load.py [--out DIR] [--dry-run]

Needs the environment variable SUPABASE_DB_PASSWORD. Host, port, database and user come from
RecipeAppBackend/src/main/resources/application.properties.

The load only ever adds rows: each file is copied into a temporary table and inserted with
ON CONFLICT DO NOTHING, so running it again skips every row that is already there. All three
tables are loaded in one transaction, so a failure leaves the database untouched. A size guard
refuses to start if the database would end up over 400 MB.

--dry-run connects, checks the tables and the size guard, prints what it found and stops
before writing anything.
"""

import argparse
import csv
import os
import sys
from pathlib import Path
from urllib.parse import urlparse

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO_ROOT / "tools/dataset/out"
PROPERTIES_FILE = REPO_ROOT / "RecipeAppBackend/src/main/resources/application.properties"
README = "tools/dataset/README.md"

PASSWORD_VARIABLE = "SUPABASE_DB_PASSWORD"
SIZE_LIMIT = 400 * 1024 * 1024
SIZE_FACTOR = 2.5  # database growth per byte of csv: row data plus indexes and the primary key
CHUNK_BYTES = 1024 * 1024

# Loaded in this order. The columns are the csv header written by prepare.py, and also the column list
# of the COPY and INSERT statements, so extra defaulted columns on a table cannot upset the load.
TABLE_COLUMNS = {
    "reference_recipes": [
        "id", "title", "ingredient_lines", "ingredient_names", "directions",
        "meal_types", "allergens", "vegetarian", "vegan", "gluten_free", "dairy_free",
    ],
    "ingredient_stats": ["name", "recipe_count"],
    "ingredient_pairings": ["ingredient", "partner", "together_count"],
}
REQUIRED_TABLES = [*TABLE_COLUMNS, "user_preferences"]  # created together by the Flyway V8 migration


# --- settings and the size guard -----------------------------------------------

def read_connection_settings(props_path):
    """Host, port, dbname and user, parsed from spring.datasource.url / spring.datasource.username."""
    values = {}
    for line in Path(props_path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()

    url, user = values.get("spring.datasource.url"), values.get("spring.datasource.username")
    if not url or not user:
        raise SystemExit(f"{props_path} must set spring.datasource.url and spring.datasource.username.")
    parsed = urlparse(url[len("jdbc:"):] if url.startswith("jdbc:") else url)
    if not parsed.hostname or not parsed.path.strip("/"):
        raise SystemExit(f"Cannot read the host and database name from spring.datasource.url in {props_path}.")
    return {"host": parsed.hostname, "port": parsed.port or 5432, "dbname": parsed.path.strip("/"), "user": user}


def projected_bytes(current, csv_bytes):
    return current + int(SIZE_FACTOR * csv_bytes)


def check_size(projected, limit=SIZE_LIMIT):
    if projected > limit:
        raise SystemExit(
            f"Refusing to load: the database would grow to about {_mb(projected)}, over the {_mb(limit)} limit. "
            "Run prepare.py with smaller --per-meal / --untagged values, then try again."
        )


def _mb(size):
    return f"{size / (1024 * 1024):,.1f} MB"


# --- connection ----------------------------------------------------------------

def get_password(environ=None):
    password = (os.environ if environ is None else environ).get(PASSWORD_VARIABLE, "")
    if not password:
        raise SystemExit(f"{PASSWORD_VARIABLE} is not set. See {README} for how to set it without printing it.")
    return password


def connect(settings, password):
    import psycopg  # imported here so the tests and --help work without it installed

    return psycopg.connect(**settings, password=password, sslmode="require", connect_timeout=15,
                           autocommit=True)


def _scrub(text, secret):
    return text.replace(secret, "***") if secret else text


# --- the load ------------------------------------------------------------------

def find_csv_files(out_dir):
    """{table: path} for the three csv files, checking they exist and carry the expected header."""
    files = {}
    for table, columns in TABLE_COLUMNS.items():
        path = Path(out_dir) / f"{table}.csv"
        if not path.is_file():
            raise SystemExit(f"Missing {path}. Run prepare.py first; see {README}.")
        with open(path, encoding="utf-8", newline="") as f:
            header = next(csv.reader(f), None)
        if header != columns:
            raise SystemExit(f"{path} has the header {header}, expected {columns}. Run prepare.py again.")
        files[table] = path
    return files


def check_tables_exist(cur):
    cur.execute(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ANY(%s)",
        (REQUIRED_TABLES,),
    )
    found = {row[0] for row in cur.fetchall()}
    if not found.issuperset(REQUIRED_TABLES):
        raise SystemExit("Restart the backend so Flyway applies V8, then run again.")


def database_size(cur):
    cur.execute("SELECT pg_database_size(current_database())")
    return int(cur.fetchone()[0])


def copy_and_insert(cur, table, path):
    """Copy the csv into a temporary table, then insert its rows. Returns (rows_in_file, inserted)."""
    columns = ", ".join(TABLE_COLUMNS[table])
    temp = f"tmp_{table}"
    cur.execute(f"CREATE TEMP TABLE {temp} (LIKE public.{table} INCLUDING DEFAULTS) ON COMMIT DROP")
    with cur.copy(f"COPY {temp} ({columns}) FROM STDIN (FORMAT csv, HEADER true)") as copy:
        with open(path, "rb") as f:
            while chunk := f.read(CHUNK_BYTES):
                copy.write(chunk)
    cur.execute(f"SELECT count(*) FROM {temp}")
    in_file = int(cur.fetchone()[0])
    cur.execute(f"INSERT INTO public.{table} ({columns}) SELECT {columns} FROM {temp} ON CONFLICT DO NOTHING")
    return in_file, cur.rowcount


def run(args, connect_fn=connect):
    password = get_password()
    settings = read_connection_settings(PROPERTIES_FILE)
    files = find_csv_files(args.out)
    csv_bytes = sum(path.stat().st_size for path in files.values())

    print(f"Connecting to {settings['host']}:{settings['port']}/{settings['dbname']} as {settings['user']}")
    try:
        with connect_fn(settings, password) as conn:
            with conn.cursor() as cur:
                check_tables_exist(cur)
                current = database_size(cur)
            projected = projected_bytes(current, csv_bytes)
            print(f"Database size: {_mb(current)}; csv files: {_mb(csv_bytes)}; "
                  f"projected after the load: {_mb(projected)} (limit {_mb(SIZE_LIMIT)})")
            check_size(projected)
            if args.dry_run:
                print("Dry run: nothing was written.")
                return 0

            results = {}
            with conn.transaction():
                with conn.cursor() as cur:
                    for table, path in files.items():
                        results[table] = copy_and_insert(cur, table, path)
            for table, (in_file, inserted) in results.items():
                print(f"{table}: {in_file:,} in file, {inserted:,} inserted, {in_file - inserted:,} skipped (already there)")
            with conn.cursor() as cur:
                print(f"Database size afterwards: {_mb(database_size(cur))}")
            return 0
    except Exception as error:  # the transaction has rolled back; keep the password out of the message
        print(f"Load failed, nothing was changed: {type(error).__name__}: {_scrub(str(error), password)}",
              file=sys.stderr)
        return 1


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Insert prepare.py's csv files into the database (insert-only).")
    p.add_argument("--out", type=Path, default=DEFAULT_OUT, help="directory holding prepare.py's csv files")
    p.add_argument("--dry-run", action="store_true", help="check the tables and the size guard, then stop")
    return p.parse_args(argv)


def main(argv=None, connect_fn=connect):
    return run(_parse_args(argv), connect_fn)


if __name__ == "__main__":
    sys.exit(main())
