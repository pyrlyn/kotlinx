use std::time::Instant;

fn main() {
    let n: i64 = 10_000_000;
    let mut sum: i64 = 0;
    let t0 = Instant::now();
    let mut i: i64 = 1;
    while i <= n {
        let sq = i * i;
        if i % 3 == 0 {
            sum += sq;
        } else {
            sum -= sq;
        }
        i += 1;
    }
    let nanos = t0.elapsed().as_nanos();
    println!("sum={sum} nanos={nanos}");
}
