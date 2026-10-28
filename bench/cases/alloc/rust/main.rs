// alloc: ROUNDS x N individually heap-allocated small objects, summed, then freed.
use std::time::Instant;
struct P { x: i64, y: i64 }
fn main() {
    const ROUNDS: i64 = 50; const N: i64 = 200_000;
    let t0 = Instant::now();
    let mut checksum: i64 = 0;
    for r in 0..ROUNDS {
        let mut v: Vec<Box<P>> = Vec::new();
        for i in 0..N { v.push(Box::new(P { x: i + r, y: (i * 7) % 1000 })); }
        for p in &v { checksum += p.x * 31 + p.y; }
    }
    println!("checksum={checksum} nanos={}", t0.elapsed().as_nanos());
}
