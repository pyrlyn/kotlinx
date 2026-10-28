// closures: closure-heavy map/filter/sum/for_each over 1M i64, 20 rounds, capturing locals.
use std::time::Instant;
fn main() {
    let n = 1_000_000i64;
    let xs: Vec<i64> = (0..n).collect();
    let t0 = Instant::now();
    let mut total = 0i64;
    let mut r = 0i64;
    while r < 20 {
        let k = r % 5 + 1;
        let ys: Vec<i64> = xs.iter().map(|it| it * k + r).collect();
        let zs: Vec<i64> = ys.iter().copied().filter(|x| x % 3 == 0).collect();
        total += zs.iter().map(|it| it & 1023).sum::<i64>();
        zs.iter().for_each(|it| total += it % 7);
        r += 1;
    }
    println!("checksum={total} nanos={}", t0.elapsed().as_nanos());
}
