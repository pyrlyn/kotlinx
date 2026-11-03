; The add swap (i+1 -> i-1) wraps only after 2^64 steps: timeout.
; Flipping the exit condition (ne -> eq) exits after one iteration; nothing observes the count, so it
; survives (a real coverage gap the report must show, not a false 'caught').
define i32 @main() {
entry:
  br label %l
l:
  %i = phi i64 [ 0, %entry ], [ %n, %l ]
  %n = add i64 %i, 1
  %c = icmp ne i64 %n, 10
  br i1 %c, label %l, label %x
x:
  ret i32 0
}
