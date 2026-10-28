// sort: fill 1M i64 from an LCG and sort them with the same hand-written quicksort.
use std::time::Instant;
fn qsort(a: &mut [i64], lo: i32, hi: i32) {
    if lo >= hi { return; }
    let p = a[((lo + hi) as u32 >> 1) as usize];
    let (mut i, mut j) = (lo, hi);
    while i <= j {
        while a[i as usize] < p { i += 1; }
        while a[j as usize] > p { j -= 1; }
        if i <= j { a.swap(i as usize, j as usize); i += 1; j -= 1; }
    }
    if lo < j { qsort(a, lo, j); }
    if i < hi { qsort(a, i, hi); }
}
fn main() {
    let n = 1_000_000usize;
    let t0 = Instant::now();
    let mut a = vec![0i64; n];
    let mut x: i64 = 12345;
    for i in 0..n {
        x = x.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        a[i] = ((x as u64) >> 20) as i64;
    }
    qsort(&mut a, 0, n as i32 - 1);
    let (mut checksum, mut bad) = (0i64, 0i64);
    for i in 0..n {
        checksum = checksum.wrapping_mul(31).wrapping_add(a[i]);
        if i > 0 && a[i - 1] > a[i] { bad += 1; }
    }
    checksum = checksum.wrapping_add(bad * 1_000_000_007);
    println!("checksum={checksum} nanos={}", t0.elapsed().as_nanos());
}
