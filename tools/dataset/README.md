# Reference recipe dataset

These scripts turn the RecipeNLG dataset into three small tables the backend uses for food
preferences and recipe suggestions:

| Table | Filled from | What it holds |
| --- | --- | --- |
| `reference_recipes` | `out/reference_recipes.csv` | about 50,000 sampled recipes with meal tags, allergens and diet flags |
| `ingredient_stats` | `out/ingredient_stats.csv` | how many recipes use each ingredient |
| `ingredient_pairings` | `out/ingredient_pairings.csv` | the ingredients each common ingredient is most often cooked with |

`prepare.py` reads the big RecipeNLG file and writes the three CSVs. `load.py` inserts them into the
Supabase database. The commands below work in Git Bash or PowerShell; run them from the repository root
(`RecipeApplication/`) unless a step says otherwise.

## Licence: the data is never committed

RecipeNLG is for non-commercial research and education only. `tools/dataset/data/` and
`tools/dataset/out/` are git-ignored. **Never commit, upload or share the dataset files or the
generated CSVs.** If `git status` ever lists a file from those folders, stop and do not add it.

## Steps

### 1. Download RecipeNLG

Go to <https://recipenlg.cs.put.poznan.pl/>, accept the terms yourself (non-commercial research and
education) and download `dataset.zip`.

- If the download page shows an error, try again later or in another browser.
- A mirror is fine only if the zip's MD5 matches `3a168dfd0912bb034225619b3586ce76`. Check it with:

  ```
  certutil -hashfile dataset.zip MD5
  ```

  If the hash differs, do not use the file.

### 2. Unzip it

Unzip so the file ends up at:

```
tools/dataset/data/full_dataset.csv
```

Create the `data` folder if it does not exist. The unzipped file is about 2 GB.

### 3. Install the Python packages

```
pip install -r tools/dataset/requirements.txt
```

### 4. Restart the backend

`load.py` needs the tables from the Flyway V8 migration. Restart the backend (`RecipeAppBackend`) once so
V8 is applied. If the tables are missing, `load.py` stops with "Restart the backend so Flyway applies V8,
then run again."

### 5. Prepare the CSVs

```
python tools/dataset/prepare.py
```

This reads all ~2.2 million recipes three times, so it can take a while (expect several minutes) and
prints progress every 100,000 rows. It writes the three CSVs into `tools/dataset/out/`. It stops with a
message if the input does not look like RecipeNLG's `full_dataset.csv`, or if more than half of its rows
could not be read.

It also writes `tools/dataset/out/rules_audit.txt` and prints its path at the end.

### 6. Skim the rules audit

`rules_audit.txt` lists the 1,500 most frequent ingredient names that no allergen or diet rule flags, one
per line with how many recipes use it, most frequent first. Open it and skim it. If any line is an
allergen (for example a cheese, a nut, a wheat product) or a meat or fish, add a term for it to
`RecipeAppBackend/src/main/resources/food-rules.csv`, run `prepare.py` again, and only then load.
The loader only ever inserts, so tags that go into the database stay as they are.

### 7. Set the database password

`load.py` reads the password from the environment variable `SUPABASE_DB_PASSWORD`. Set it without
printing it. The password is stored in the backend's IntelliJ settings (`RecipeAppBackend/.idea/workspace.xml`); the commands below read it from there.

Git Bash, from `RecipeAppBackend/`:

```bash
cd RecipeAppBackend
export SUPABASE_DB_PASSWORD="$(python -c "import re;s=open('.idea/workspace.xml',encoding='utf-8').read();m=re.search(r'name=\"SUPABASE_DB_PASSWORD\" value=\"([^\"]*)\"',s);print(m.group(1) if m else '')")"
cd ..
```

PowerShell, from `RecipeAppBackend\`:

```powershell
cd RecipeAppBackend
$env:SUPABASE_DB_PASSWORD = python -c "import re;s=open('.idea/workspace.xml',encoding='utf-8').read();m=re.search(r'name=\x22SUPABASE_DB_PASSWORD\x22 value=\x22([^\x22]*)\x22',s);print(m.group(1) if m else '')"
cd ..
```

The variable only lasts for that terminal window. Do not echo it, paste it into chat, or write it to a
file.

### 8. Check, then load

First a dry run. It connects, checks the tables exist and that the size limit allows the load, prints
how many rows each CSV holds, then stops without writing anything:

```
python tools/dataset/load.py --dry-run
```

If it looks right, load for real:

```
python tools/dataset/load.py
```

It prints, per table, how many rows were in the file, how many were inserted and how many were skipped,
then the database size afterwards.

## Good to know

- **Re-running `load.py` is safe.** It only inserts: rows whose key already exists are skipped, so a second
  run adds nothing and changes nothing. All three tables load in one transaction, so a failed run leaves
  the database as it was.
- **There is deliberately no delete or replace mode.** `load.py` can only add rows. If a reference row ever
  needs removing or changing, that is a deliberate manual step, not something the loader does.
- **Size guard.** The database is on a small plan. `load.py` refuses to start if the current database size
  plus 2.5 times the size of the CSVs would be over 400 MB. In that case run `prepare.py` again with
  `--per-meal` and `--untagged` set lower.
- **Options.** Both scripts accept `--out DIR` for the CSV folder; `prepare.py` also takes `--input PATH`,
  `--min-common`, `--per-meal` and `--untagged` (see `python tools/dataset/prepare.py --help`).
- **Tests.** `python -m pytest tools/dataset/tests -q` needs no database and no dataset.
