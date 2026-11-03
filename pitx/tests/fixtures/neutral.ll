; Equivalent / neutral mutants: none of them may be reported as caught.
;  - @dead is never called: its icmp and add survive.
;  - the select has identical arms, so flipping its condition changes nothing.
;  - `eq` vs `ne` on the same operand pair feeding a branch whose targets are the same block.
declare i32 @printf(ptr, ...)
@fmt = private constant [4 x i8] c"%d\0A\00"

define i32 @dead(i32 %x) {
  %c = icmp eq i32 %x, 1
  %y = add i32 %x, 1
  %r = select i1 %c, i32 %y, i32 0
  ret i32 %r
}

define i32 @main() {
entry:
  %c = icmp slt i32 1, 2
  %v = select i1 %c, i32 42, i32 42
  %e = icmp eq i32 %v, 42
  br i1 %e, label %out, label %out
out:
  call i32 (ptr, ...) @printf(ptr @fmt, i32 %v)
  ret i32 0
}
