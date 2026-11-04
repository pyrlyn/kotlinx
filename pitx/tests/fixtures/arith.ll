; Arithmetic: add/sub/mul with constants. Expected: add nsw and sub caught; the add of 0 is an
; equivalent mutant (x+0 == x-0) and must survive; mul is not a site for these operators.
declare i32 @printf(ptr, ...)
@fmt = private constant [4 x i8] c"%d\0A\00"

define i32 @f(i32 %x) {
  %a = add nsw i32 %x, 10
  %b = sub i32 %a, 3
  %c = mul i32 %b, 2
  %d = add i32 %c, 0
  ret i32 %d
}

define i32 @main() {
  %r = call i32 @f(i32 5)
  call i32 (ptr, ...) @printf(ptr @fmt, i32 %r)
  ret i32 0
}
