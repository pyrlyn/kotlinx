"""pitx tests: operator unit tests (exact IR), site enumeration, and runner classification on fixtures.

Run: python3 -m unittest discover -s pitx/tests   (needs llvm-as and lli from the repo's mise LLVM tools)
"""
import json, os, sys, tempfile, unittest
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import pitx  # noqa: E402

_read = pitx._read
FIX = os.path.join(HERE, "fixtures")


def fx(name):
    return os.path.join(FIX, name)


class IcmpFlipTest(unittest.TestCase):
    def test_all_predicates_flip_to_negation(self):
        for p, q in pitx.ICMP_FLIP.items():
            ir = f"define i1 @f(i32 %a) {{\n  %c = icmp {p} i32 %a, 0\n  ret i1 %c\n}}"
            [m] = pitx.enumerate_sites(ir, ["icmp-flip"])
            self.assertEqual((m.original, m.replacement, m.line, m.function), (p, q, 2, "f"))
            self.assertEqual(pitx.apply(ir, m), ir.replace(f"icmp {p} ", f"icmp {q} "))

    def test_flip_is_involution(self):
        self.assertTrue(all(pitx.ICMP_FLIP[pitx.ICMP_FLIP[p]] == p for p in pitx.ICMP_FLIP))

    def test_fcmp_and_comments_are_not_sites(self):
        ir = ("define i1 @f(double %a) {\n  %c = fcmp olt double %a, 0.0 ; icmp eq in a comment\n"
              "  ret i1 %c\n}")
        self.assertEqual(pitx.enumerate_sites(ir), [])


class ArithSwapTest(unittest.TestCase):
    def test_add_to_sub_exact(self):
        ir = "define i32 @f(i32 %a) {\n  %r = add i32 %a, 7\n  ret i32 %r\n}"
        [m] = pitx.enumerate_sites(ir, ["arith-swap"])
        self.assertEqual(pitx.apply(ir, m), "define i32 @f(i32 %a) {\n  %r = sub i32 %a, 7\n  ret i32 %r\n}")

    def test_sub_to_add_drops_wrap_flags(self):
        ir = "define i32 @f(i32 %a) {\n  %r = sub nuw nsw i32 %a, 7\n  ret i32 %r\n}"
        [m] = pitx.enumerate_sites(ir, ["arith-swap"])
        self.assertEqual(m.original, "sub nuw nsw")
        self.assertEqual(pitx.apply(ir, m), "define i32 @f(i32 %a) {\n  %r = add i32 %a, 7\n  ret i32 %r\n}")

    def test_float_and_mul_are_not_sites(self):
        ir = ("define double @f(double %a, i32 %b) {\n  %x = fadd double %a, 1.0\n  %y = fsub double %x, 1.0\n"
              "  %z = mul i32 %b, 2\n  ret double %y\n}")
        self.assertEqual(pitx.enumerate_sites(ir), [])


class EnumerationTest(unittest.TestCase):
    def test_sites_in_order_with_functions(self):
        ir = _read(fx("cond.ll"))
        got = [(m.op, m.function, m.original) for m in pitx.enumerate_sites(ir)]
        self.assertEqual(got, [("icmp-flip", "max", "sgt"), ("icmp-flip", "sign", "slt")])

    def test_globals_and_declarations_are_skipped(self):
        ir = "@g = global i32 add (i32 1, i32 2)\ndeclare i32 @h(i32)\n"
        self.assertEqual(pitx.enumerate_sites(ir), [])

    def test_skip_functions(self):
        import re
        ir = _read(fx("neutral.ll"))
        self.assertEqual({m.function for m in pitx.enumerate_sites(ir, skip_functions=re.compile("^dead$"))}, {"main"})

    def test_reachability_skips_dead_functions(self):
        ir = _read(fx("neutral.ll"))
        self.assertEqual(pitx.reachable_functions(ir), {"main", "printf", "fmt"})
        self.assertEqual({m.function for m in pitx.enumerate_sites(ir, only_reachable=True)}, {"main"})

    def test_reachability_through_globals(self):
        ir = ("@vt = constant [1 x ptr] [ptr @impl]\n"
              "define i32 @impl() {\n  %a = add i32 1, 2\n  ret i32 %a\n}\n"
              "define i32 @unused() {\n  %a = add i32 1, 2\n  ret i32 %a\n}\n"
              "define i32 @main() {\n  %f = load ptr, ptr @vt\n  %r = call i32 %f()\n  ret i32 %r\n}\n")
        self.assertEqual([m.function for m in pitx.enumerate_sites(ir, only_reachable=True)], ["impl"])

    def test_debug_location_mapping(self):
        ir = ("define i32 @f(i32 %a) !dbg !3 {\n  %r = add i32 %a, 1, !dbg !7\n  ret i32 %r\n}\n"
              "!7 = !DILocation(line: 42, column: 5, scope: !3)\n")
        [m] = pitx.enumerate_sites(ir)
        self.assertEqual(m.loc, "line 42")

    def test_apply_does_not_touch_input(self):
        path = fx("arith.ll"); before = _read(path)
        pitx.analyze(path, pitx.LliHarness(5))
        self.assertEqual(_read(path), before)


# Expected classification per fixture: list of (op, function, original, status) in IR order.
EXPECT = {
    "cond.ll": [("icmp-flip", "max", "sgt", "caught"), ("icmp-flip", "sign", "slt", "caught")],
    "arith.ll": [("arith-swap", "f", "add nsw", "caught"), ("arith-swap", "f", "sub", "caught"),
                 ("arith-swap", "f", "add", "survived")],          # x+0 == x-0: equivalent mutant
    "memory.ll": [("arith-swap", "main", "add", "caught"), ("icmp-flip", "main", "ult", "caught")],
    "neutral.ll": [("icmp-flip", "dead", "eq", "survived"), ("arith-swap", "dead", "add", "survived"),
                   ("icmp-flip", "main", "slt", "survived"), ("icmp-flip", "main", "eq", "survived")],
    "loop_timeout.ll": [("arith-swap", "main", "add", "timeout"), ("icmp-flip", "main", "ne", "survived")],
    "exitcode.ll": [("arith-swap", "main", "sub", "caught")],
}


class RunnerClassificationTest(unittest.TestCase):
    def check(self, name):
        rep = pitx.analyze(fx(name), pitx.LliHarness(timeout=2))
        got = [(m["op"], m["function"], m["original"], m["status"]) for m in rep["mutants"]]
        self.assertEqual(got, EXPECT[name])
        json.dumps(rep)  # report is JSON-serializable
        return rep

    def test_cond(self): self.check("cond.ll")
    def test_arith(self): self.check("arith.ll")
    def test_memory(self): self.check("memory.ll")
    def test_exitcode(self): self.check("exitcode.ll")
    def test_timeout(self): self.check("loop_timeout.ll")

    def test_neutral_no_false_positives(self):
        rep = self.check("neutral.ll")
        self.assertEqual(rep["counts"]["caught"], 0)
        self.assertEqual(rep["score"], 0.0)

    def test_score(self):
        rep = self.check("arith.ll")
        self.assertEqual(rep["counts"], {"caught": 2, "survived": 1, "timeout": 0, "invalid": 0})
        self.assertAlmostEqual(rep["score"], 2 / 3)


class InvalidTest(unittest.TestCase):
    def test_invalid_inputs_rejected(self):
        for name in ("invalid_syntax.ll", "invalid_types.ll"):
            with self.subTest(name):
                with self.assertRaises(pitx.InvalidInput):
                    pitx.analyze(fx(name), pitx.LliHarness(2))
                self.assertEqual(pitx.main([fx(name)]), 2)

    def test_mutant_producing_invalid_ir_is_invalid_not_caught(self):
        # A deliberately broken operator: rewrites the icmp predicate into a non-existent one.
        def bogus(line):
            for s, e, o, r in pitx._icmp(line):
                yield s, e, o, "bogus"
        pitx.OPERATORS["bogus"] = bogus
        try:
            rep = pitx.analyze(fx("cond.ll"), pitx.LliHarness(2), ops=["bogus"])
        finally:
            del pitx.OPERATORS["bogus"]
        self.assertEqual([m["status"] for m in rep["mutants"]], ["invalid", "invalid"])
        self.assertIsNone(rep["score"])

    def test_stale_site_is_invalid(self):
        ir = _read(fx("exitcode.ll"))
        m = pitx.Mutant(0, "arith-swap", 1, "main", "sub", "add")  # line 1 is a comment
        self.assertEqual(pitx.run_mutant(ir, m, pitx.LliHarness(2), ("", 5)).status, "invalid")


class ReportTest(unittest.TestCase):
    def test_cli_json_and_text(self):
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, "r.json")
            self.assertEqual(pitx.main([fx("arith.ll"), "--json", out]), 0)
            with open(out) as f:
                rep = json.load(f)
        self.assertEqual(rep["baseline"], {"stdout": "24\n", "exit": 0})
        self.assertIn("SURVIVED #2 arith-swap @f", pitx.text_report(rep))


if __name__ == "__main__":
    unittest.main()
