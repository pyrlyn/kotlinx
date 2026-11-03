# Borrowed field-read locals

`val p = e.f` normally retains the loaded reference and releases it after the last use of `p`. Under
`--gc=epoch` that is two log entries per local (an increment, a decrement), and the decrement also makes the
object a possible cycle root. `NativeBackend.borrowableFieldLocal` skips both when it can prove the object
stays referenced by `e.f` for the whole live range of `p`. `NEWKOTLIN_NO_BORROW_FIELD_LOCALS=1` turns it off
(A/B runs).

## Rule

`val p = e.f` (or `val p = f` for a field of `this`) is borrowed when all of these hold:

1. **Base.** `e` is `this` of the method or a parameter. Both are borrowed from the caller, alive for the
   whole call (the caller holds a counted reference) and cannot be reassigned.
2. **Field.** `f` is a plain backing field of a class: no custom getter, setter or property accessor, so
   the read and every store to `f` are plain memory accesses.
3. **Range.** From the declaration to the statement holding the last use of `p` (same block), the code
   contains only names, literals, null and identity comparisons, `!`, `&&`, `||`, `if`, plain field reads,
   plain field stores `x.g = v` and nested `val`s initialized from those. No calls, allocations, lambdas,
   loops, `return`, `throw`, `when`, string templates or operator overloads, so no code we do not see runs.
4. **No overwrite.** No store in the range writes a field named `f` of any class (by name, so whether some
   `x` aliases `e` does not matter), unless the stored value is `p` itself. Storing `p` into `e.f` leaves
   `e.f` unchanged.

## Why it is safe

- From the read to the last use, `e.f` holds a counted reference to `p`'s object. Rules 3 and 4 ensure it
  is not overwritten, and rule 1 ensures `e` is not freed: only code in the range could release the
  caller's reference, and that code has no calls.
- **Plain RC (default, rc, incremental):** a count with a reference from `e.f` is at least 1. Releases in
  the range, such as the old value of a store or other locals dropped at their last use, may cascade, but
  each one decrements by 1 for a reference it owned. None of them can take away `e.f`'s reference.
- **Cycle collectors:** the object is reachable from `e`, which is reachable from the caller's counted
  reference, so trial deletion finds an external reference and keeps it.
- **Deferred RC (epoch):** an object is freed only after the collector applies a decrement that brings its
  count to zero (or after validating a garbage cycle it belongs to). The decrement for `e.f` would come
  only from overwriting `e.f`, which rule 4 excludes. The collector's lagging counts are never lower than
  the true count of logged references, because each log applies its increments before its decrements.
- Uses that need ownership still get it. Storing `p` into a field, returning it or passing it as an owned
  argument retain it there, as for any other borrowed value (parameters, smart-cast aliases).

## Not covered (stays owned)

- A store to the same field in the range: `val h = head; head = null; use(h)`, even through an alias
  (`val p = a.next; b.next = null`).
- Any call in the range (`clear(a)` may overwrite `a.next`).
- `var` locals, explicitly typed locals and locals with a base that is itself a local.

`compiler/tests/leaks/borrowed_field_locals.kt` covers the borrowed shapes (LRU unlink and pushFront) and
the shapes above under leak count, ASan with immediate frees, and macOS `leaks`.
