# Agent notes

Start with [`compiler/docs/ARCHITECTURE.md`](compiler/docs/ARCHITECTURE.md) (pipeline, key files, how to add a feature,
regression checklist) and `STATUS.md`. GC: `compiler/gc/docs/architecture.md`.

Rules: one feature per PR from fresh main; upstream tests stay byte-for-byte unmodified; no new dependencies without
approval; docs, comments and commits in English.
GC tests: all new GC tests are written in Zig, in `compiler/gc/tests/zig` (see its README); do not add new C
or shell GC tests. `compiler/gc/tests/zig/compare.sh` checks the port against `compiler/gc/tests/run.sh`.
