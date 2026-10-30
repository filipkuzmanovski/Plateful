import re
from contextlib import contextmanager
from pathlib import Path

import pytest

import load
from load import check_size, projected_bytes, read_connection_settings

PASSWORD = "s3cret-pw-value"
MB = 1024 * 1024


class FakeCursor:
    """Records SQL; answers the loader's few SELECTs from `db`."""

    def __init__(self, db):
        self.db = db
        self.rowcount = -1
        self._row = None
        self._rows = []

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql, params=None):
        self.db.executed.append(sql)
        self.rowcount = -1
        if "information_schema.tables" in sql:
            self._rows = [(t,) for t in self.db.tables]
        elif "pg_database_size" in sql:
            self._row = (self.db.size_bytes,)
        elif sql.startswith("SELECT count(*)"):
            self._row = (self.db.copied_rows,)
        elif sql.startswith("INSERT INTO"):
            self.rowcount = self.db.inserted_rows

    def fetchall(self):
        return self._rows

    def fetchone(self):
        return self._row

    @contextmanager
    def copy(self, sql):
        self.db.executed.append(sql)
        db = self.db

        class Sink:
            def write(self, chunk):
                db.copied_bytes += len(chunk)

        yield Sink()


class FakeConnection:
    def __init__(self, tables=None, size_bytes=50 * MB, copied_rows=10, inserted_rows=7):
        self.tables = tables if tables is not None else list(load.REQUIRED_TABLES)
        self.size_bytes = size_bytes
        self.copied_rows = copied_rows
        self.inserted_rows = inserted_rows
        self.executed = []
        self.copied_bytes = 0
        self.transactions = 0

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def cursor(self):
        return FakeCursor(self)

    @contextmanager
    def transaction(self):
        self.transactions += 1
        yield

    def statements(self, prefix):
        return [s for s in self.executed if s.startswith(prefix)]


@pytest.fixture()
def out_dir(tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    for table, columns in load.TABLE_COLUMNS.items():
        (out / f"{table}.csv").write_text(",".join(columns) + "\nrow\n", encoding="utf-8")
    return out


@pytest.fixture()
def with_password(monkeypatch):
    monkeypatch.setenv(load.PASSWORD_VARIABLE, PASSWORD)


def run_main(out_dir, conn, *flags):
    return load.main(["--out", str(out_dir), *flags], connect_fn=lambda settings, password: conn)


# --- read_connection_settings -------------------------------------------------

def test_read_connection_settings_parses_the_datasource_url_and_user(tmp_path):
    props = tmp_path / "application.properties"
    props.write_text(
        "# comment\n"
        "spring.application.name=x\n"
        "spring.datasource.url=jdbc:postgresql://aws-0-eu-north-1.pooler.supabase.com:5432/postgres\n"
        "spring.datasource.username=postgres.abcdef\n"
        "spring.datasource.password=${SUPABASE_DB_PASSWORD}\n",
        encoding="utf-8",
    )
    assert read_connection_settings(props) == {
        "host": "aws-0-eu-north-1.pooler.supabase.com",
        "port": 5432,
        "dbname": "postgres",
        "user": "postgres.abcdef",
    }


def test_read_connection_settings_defaults_the_port(tmp_path):
    props = tmp_path / "p.properties"
    props.write_text("spring.datasource.url=jdbc:postgresql://db.example.com/app\n"
                     "spring.datasource.username=u\n", encoding="utf-8")
    assert read_connection_settings(props)["port"] == 5432


def test_read_connection_settings_rejects_a_file_without_the_datasource(tmp_path):
    props = tmp_path / "p.properties"
    props.write_text("a=b\n", encoding="utf-8")
    with pytest.raises(SystemExit):
        read_connection_settings(props)


def test_the_real_properties_file_is_readable():
    settings = read_connection_settings(load.PROPERTIES_FILE)
    assert settings["host"] == "aws-0-eu-north-1.pooler.supabase.com"
    assert settings["port"] == 5432 and settings["dbname"] == "postgres"


# --- size guard -----------------------------------------------------------------

def test_projected_bytes_adds_two_and_a_half_times_the_csv_size():
    assert projected_bytes(100, 1000) == 2600
    assert projected_bytes(0, 3) == 7  # int(7.5)


def test_check_size_passes_at_the_limit_and_raises_over_it():
    check_size(400 * MB)
    with pytest.raises(SystemExit) as error:
        check_size(400 * MB + 1)
    assert "400" in str(error.value)
    check_size(10, limit=10)
    with pytest.raises(SystemExit):
        check_size(11, limit=10)


# --- password -------------------------------------------------------------------

@pytest.mark.parametrize("value", [None, ""])
def test_a_missing_password_exits_with_a_message_that_holds_no_value(monkeypatch, out_dir, value):
    if value is None:
        monkeypatch.delenv(load.PASSWORD_VARIABLE, raising=False)
    else:
        monkeypatch.setenv(load.PASSWORD_VARIABLE, value)
    conn = FakeConnection()
    with pytest.raises(SystemExit) as error:
        run_main(out_dir, conn)
    message = str(error.value)
    assert error.value.code != 0
    assert load.PASSWORD_VARIABLE in message
    assert "=" not in message
    assert conn.executed == []


def test_a_failure_never_prints_the_password(with_password, out_dir, capsys):
    def failing_connect(settings, password):
        raise RuntimeError(f"connection to server failed: password {password} rejected")

    code = load.main(["--out", str(out_dir)], connect_fn=failing_connect)
    captured = capsys.readouterr()
    assert code == 1
    assert PASSWORD not in captured.out + captured.err
    assert "nothing was changed" in captured.err


# --- the run --------------------------------------------------------------------

def test_dry_run_executes_no_insert_or_copy(with_password, out_dir, capsys):
    conn = FakeConnection()
    assert run_main(out_dir, conn, "--dry-run") == 0
    assert conn.statements("INSERT") == []
    assert conn.statements("COPY") == []
    assert conn.statements("CREATE") == []
    assert conn.copied_bytes == 0 and conn.transactions == 0
    assert conn.statements("SELECT")  # it did check the tables and the size
    out = capsys.readouterr().out
    assert "Dry run" in out and "projected" in out
    assert PASSWORD not in out


def test_missing_tables_ask_for_a_backend_restart(with_password, out_dir):
    conn = FakeConnection(tables=["reference_recipes", "ingredient_stats", "ingredient_pairings"])
    with pytest.raises(SystemExit) as error:
        run_main(out_dir, conn)
    assert "Restart the backend so Flyway applies V8, then run again." in str(error.value)
    assert conn.statements("INSERT") == []


def test_the_size_guard_stops_the_load_before_any_write(with_password, out_dir):
    conn = FakeConnection(size_bytes=399 * MB)
    (out_dir / "reference_recipes.csv").write_bytes(
        ",".join(load.TABLE_COLUMNS["reference_recipes"]).encode() + b"\n" + b"x" * (5 * MB))
    with pytest.raises(SystemExit):
        run_main(out_dir, conn)
    assert conn.statements("INSERT") == [] and conn.statements("COPY") == []


def test_the_load_copies_and_inserts_each_table_in_one_transaction(with_password, out_dir, capsys):
    conn = FakeConnection(copied_rows=10, inserted_rows=7)
    assert run_main(out_dir, conn) == 0

    assert conn.transactions == 1
    assert len(conn.statements("CREATE TEMP TABLE")) == 3
    assert all("ON COMMIT DROP" in s and "LIKE public." in s for s in conn.statements("CREATE TEMP TABLE"))
    copies = conn.statements("COPY")
    assert len(copies) == 3
    assert all("FORMAT csv, HEADER true" in s and "FROM STDIN" in s for s in copies)
    inserts = conn.statements("INSERT INTO public.")
    assert [re.match(r"INSERT INTO public\.(\w+)", s).group(1) for s in inserts] == list(load.TABLE_COLUMNS)
    assert all(s.endswith("ON CONFLICT DO NOTHING") for s in inserts)
    assert conn.copied_bytes > 0

    out = capsys.readouterr().out
    assert "reference_recipes: 10 in file, 7 inserted, 3 skipped" in out
    assert "Database size afterwards" in out


def test_the_copy_and_insert_name_the_columns(with_password, out_dir):
    conn = FakeConnection()
    run_main(out_dir, conn)
    recipes_columns = ", ".join(load.TABLE_COLUMNS["reference_recipes"])
    assert any(f"COPY tmp_reference_recipes ({recipes_columns})" in s for s in conn.statements("COPY"))
    assert any(f"INSERT INTO public.reference_recipes ({recipes_columns}) SELECT {recipes_columns}" in s
               for s in conn.statements("INSERT"))


def test_a_missing_csv_file_points_to_prepare(with_password, out_dir):
    (out_dir / "ingredient_stats.csv").unlink()
    with pytest.raises(SystemExit) as error:
        run_main(out_dir, FakeConnection())
    assert "prepare.py" in str(error.value)


def test_a_csv_with_the_wrong_header_is_refused(with_password, out_dir):
    (out_dir / "ingredient_stats.csv").write_text("name,count\nx,1\n", encoding="utf-8")
    with pytest.raises(SystemExit):
        run_main(out_dir, FakeConnection())


def test_a_failure_during_the_load_is_reported_and_exits_non_zero(with_password, out_dir, capsys):
    class Exploding(FakeConnection):
        def cursor(self):
            cursor = super().cursor()
            original = cursor.execute

            def execute(sql, params=None):
                if sql.startswith("INSERT"):
                    raise RuntimeError("boom")
                return original(sql, params)

            cursor.execute = execute
            return cursor

    assert run_main(out_dir, Exploding()) == 1
    assert "boom" in capsys.readouterr().err


# --- source hygiene ----------------------------------------------------------------

def test_the_loader_source_has_no_remove_or_replace_mode():
    source = Path(load.__file__).read_text(encoding="utf-8")
    assert "TRUNCATE" not in source.upper()
    assert "DELETE" not in source.upper()
    assert "DROP TABLE" not in source.upper()
