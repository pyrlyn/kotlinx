# Upstream Kotlin tests for Kotlin-X compiler

Tests are imported automatically from JetBrains/kotlin (pinned commit) — never copied by hand.

- `import.py` — sparse/blobless/shallow fetch at `PINNED_COMMIT`, selects single-file tests from
  `compiler/testData/codegen/{box,boxInline}`, `native/native.tests/testData`, `kotlin-native/backend.native/tests`,
  applies `patches/<path>.patch`, runs everything on newkotlinc and keeps only PASSING tests in `box/` and `native/`;
  writes `manifest.json` (commit, every candidate with status/category/detail).
  `python3 compiler/tests/upstream/import.py [--cache DIR] [--nkc PATH]`
- `run.py` — re-runs the kept tests: `python3 compiler/tests/upstream/run.py` (box tests: driver calls `box()`, pass = `OK`;
  native tests with `main`: stdout must equal the `.out` golden). Directives (TARGET_BACKEND, IGNORE_BACKEND NATIVE,
  WITH_REFLECT, WITH_COROUTINES, FILE/MODULE, JVM imports, ...) make a test N/A.
- `report.py` — regenerates `report.md` from `manifest.json`.
- `bugs/` — minimal repros of miscompiles/crashes found by the import (expected vs actual in each header).
- Licensing: upstream tests are Apache-2.0, see `LICENSE` and `NOTICE`.
