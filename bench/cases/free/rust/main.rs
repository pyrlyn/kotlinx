// free: 20 rounds; fill 200k boxed objects of three sizes, replace every third (freeing the old), drop all.
use std::time::Instant;
trait Obj { fn v(&self) -> i64; }
struct S1 { a: i64 }
struct S3 { a: i64, b: i64, c: i64 }
struct S6 { a: i64, b: i64, c: i64, d: i64, e: i64, f: i64 }
impl Obj for S1 { fn v(&self) -> i64 { self.a } }
impl Obj for S3 { fn v(&self) -> i64 { self.a + self.b + self.c } }
impl Obj for S6 { fn v(&self) -> i64 { self.a + self.b + self.c + self.d + self.e + self.f } }
fn mk(i: i64) -> Box<dyn Obj> {
    match i % 3 { 0 => Box::new(S1 { a: i }), 1 => Box::new(S3 { a: i, b: 1, c: 2 }), _ => Box::new(S6 { a: i, b: 1, c: 2, d: 3, e: 4, f: 5 }) }
}
fn main() {
    let n: i64 = 200_000;
    let t0 = Instant::now();
    let mut checksum: i64 = 0;
    for r in 0..20i64 {
        let mut xs: Vec<Box<dyn Obj>> = Vec::new();
        for i in 0..n { xs.push(mk(i + r)); }
        let mut i = 0;
        while i < n { xs[i as usize] = mk(i * 7); i += 3; }
        for o in &xs { checksum += o.v(); }
    }
    println!("checksum={checksum} nanos={}", t0.elapsed().as_nanos());
}
