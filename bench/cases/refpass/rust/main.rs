// refpass: 30M calls passing objects by reference (borrows), plus a shared Rc stored in a mutable field.
use std::rc::Rc;
use std::time::Instant;
struct P { x: i64, y: i64 }
struct Holder { p: Rc<P> }
fn score(a: &P, b: &P) -> i64 { a.x * b.y - a.y * b.x }
fn pick<'a>(a: &'a Rc<P>, b: &'a Rc<P>, k: i64) -> &'a Rc<P> { if k & 1 == 0 { a } else { b } }
fn main() {
    let pool: Vec<Rc<P>> = (0..1000i64).map(|i| Rc::new(P { x: i, y: i * 7 % 13 })).collect();
    let mut h = Holder { p: pool[0].clone() };
    let t0 = Instant::now();
    let mut checksum: i64 = 0;
    let mut k: i64 = 0;
    while k < 30_000_000 {
        let a = &pool[(k % 1000) as usize];
        let b = h.p.clone();
        let c = pick(a, &b, k);
        checksum += score(a, c);
        if k % 4 == 0 { h.p = a.clone(); }
        k += 1;
    }
    println!("checksum={checksum} nanos={}", t0.elapsed().as_nanos());
}
