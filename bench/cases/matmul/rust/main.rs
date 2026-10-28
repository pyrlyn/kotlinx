// matmul: 256x256 f64 matrix multiply (i-k-j order), 4 repetitions.
use std::time::Instant;
fn main() {
    let n = 256usize;
    let mut a = vec![0f64; n * n]; let mut b = vec![0f64; n * n]; let mut c = vec![0f64; n * n];
    for i in 0..n * n { a[i] = (i % 7) as f64; b[i] = (i % 5) as f64 - 2.0; }
    let t0 = Instant::now();
    let mut total = 0.0;
    for rep in 0..4 {
        for i in 0..n * n { c[i] = 0.0; }
        for i in 0..n {
            for k in 0..n {
                let aik = a[i * n + k] + rep as f64;
                for j in 0..n { c[i * n + j] += aik * b[k * n + j]; }
            }
        }
        for i in 0..n * n { total += c[i]; }
    }
    let checksum = total as i64;
    println!("checksum={checksum} nanos={}", t0.elapsed().as_nanos());
}
