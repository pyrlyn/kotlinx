; Conditions: icmp feeding br and select. Expected: both icmp flips are caught.
declare i32 @printf(ptr, ...)
@fmt = private constant [4 x i8] c"%d\0A\00"

define i32 @max(i32 %a, i32 %b) {
  %c = icmp sgt i32 %a, %b
  %r = select i1 %c, i32 %a, i32 %b
  ret i32 %r
}

define i32 @sign(i32 %x) {
entry:
  %neg = icmp slt i32 %x, 0
  br i1 %neg, label %n, label %p
n:
  ret i32 -1
p:
  ret i32 1
}

define i32 @main() {
  %m = call i32 @max(i32 3, i32 7)
  %s = call i32 @sign(i32 -5)
  call i32 (ptr, ...) @printf(ptr @fmt, i32 %m)
  call i32 (ptr, ...) @printf(ptr @fmt, i32 %s)
  ret i32 0
}
