# Testing


```
compiler/test.sh                             # golden tests, benchmark checksums, leak tests, GC tests
python3 compiler/tests/upstream/run.py       # kept upstream Kotlin tests (unmodified)
```

Upstream tests in `compiler/tests/upstream` stay byte-for-byte unmodified.
