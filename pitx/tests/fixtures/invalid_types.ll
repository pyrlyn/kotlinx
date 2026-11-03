; Invalid input: type mismatch (i32 returned from an i64 function).
define i64 @main() {
  %a = add i32 1, 2
  ret i32 %a
}
