; Invalid input: undefined value %nope. pitx must reject it before mutating.
define i32 @main() {
  %a = add i32 %nope, 1
  ret i32 %a
}
