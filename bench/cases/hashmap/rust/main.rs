// hashmap: 1M i32->i64 inserts, then 3M lookups (1/3 hits) with the standard HashMap (default hasher).
use std::collections::HashMap;
use std::time::Instant;
fn main() {
    let n: i32 = 1_000_000;
    let t0 = Instant::now();
    let mut m: HashMap<i32, i64> = HashMap::new();
    for i in 0..n { m.insert(i * 3, i as i64 * 2); }
    let (mut sum, mut miss) = (0i64, 0i64);
    for j in 0..3 * n {
        if m.contains_key(&j) { sum += m[&j]; } else { miss += 1; }
    }
    let checksum = sum + miss * 1_000_003 + m.len() as i64;
    println!("checksum={checksum} nanos={}", t0.elapsed().as_nanos());
}
