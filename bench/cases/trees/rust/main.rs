// trees: binary-trees style (depths 4..16 step 2 + one long-lived tree); Box ownership, freed on drop.
use std::time::Instant;
struct Tree { l: Option<Box<Tree>>, r: Option<Box<Tree>> }
fn make(d: i32) -> Box<Tree> {
    if d == 0 { Box::new(Tree { l: None, r: None }) } else { Box::new(Tree { l: Some(make(d - 1)), r: Some(make(d - 1)) }) }
}
fn check(t: &Option<Box<Tree>>) -> i64 { match t { None => 0, Some(t) => 1 + check(&t.l) + check(&t.r) } }
fn main() {
    let (min_d, max_d) = (4, 16);
    let t0 = Instant::now();
    let long_lived = Some(make(max_d));
    let mut checksum: i64 = 0;
    let mut d = min_d;
    while d <= max_d {
        let iters = 1 << (max_d - d + min_d);
        for _ in 0..iters { let t = Some(make(d)); checksum += check(&t); }
        d += 2;
    }
    checksum += check(&long_lived);
    println!("checksum={checksum} nanos={}", t0.elapsed().as_nanos());
}
