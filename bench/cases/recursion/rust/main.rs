// recursion: naive fib(32) + deep non-tail recursion depth 100_000.
use std::time::Instant;
#[inline(never)] fn fib(n: i64) -> i64 { if n < 2 { n } else { fib(n - 1) + fib(n - 2) } }
#[inline(never)] fn deep(n: i64) -> i64 { if n == 0 { 0 } else { n + deep(n - 1) } }
fn main() {
    let n = std::hint::black_box(32i64); let d = std::hint::black_box(100_000i64);
    let t0 = Instant::now();
    let checksum = fib(n) + deep(d);
    println!("checksum={checksum} nanos={}", t0.elapsed().as_nanos());
}
