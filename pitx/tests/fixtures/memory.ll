; Memory ops: alloca/store/load/GEP; the index add and the loop bound icmp are sites.
; Expected: the index add is caught (reads a different slot), the loop icmp flip is caught (loop body skipped).
declare i32 @printf(ptr, ...)
@fmt = private constant [4 x i8] c"%d\0A\00"

define i32 @main() {
entry:
  %arr = alloca [4 x i32]
  %i = alloca i32
  store i32 0, ptr %i
  br label %fill
fill:
  %iv = load i32, ptr %i
  %p = getelementptr [4 x i32], ptr %arr, i32 0, i32 %iv
  %v = mul i32 %iv, 10
  store i32 %v, ptr %p
  %nx = add i32 %iv, 1
  store i32 %nx, ptr %i
  %more = icmp ult i32 %nx, 4
  br i1 %more, label %fill, label %done
done:
  %q = getelementptr [4 x i32], ptr %arr, i32 0, i32 2
  %x = load i32, ptr %q
  call i32 (ptr, ...) @printf(ptr @fmt, i32 %x)
  ret i32 0
}
