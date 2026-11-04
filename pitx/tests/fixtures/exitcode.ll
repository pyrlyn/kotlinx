; Result observed only through the exit code (no stdout). sub swap caught via exit code.
define i32 @main() {
  %a = sub i32 9, 4
  ret i32 %a
}
