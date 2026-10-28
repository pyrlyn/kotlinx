// file: read data/words.txt, count lines and words, checksum h = h*31 + byte (wrapping i64).
use std::time::Instant;
fn main() {
    let path = std::env::var("BENCH_DATA").unwrap_or("data/words.txt".into());
    let t0 = Instant::now();
    let buf = std::fs::read(&path).expect("read data file");
    let (mut lines, mut words, mut h, mut inw) = (0i64, 0i64, 0i64, false);
    for &b in &buf {
        h = h.wrapping_mul(31).wrapping_add(b as i64);
        if b == b'\n' { lines += 1; }
        let ws = b == b' ' || b == b'\n';
        if !ws && !inw { words += 1; }
        inw = !ws;
    }
    let checksum = h.wrapping_add(lines.wrapping_mul(1_000_003)).wrapping_add(words);
    println!("checksum={checksum} lines={lines} words={words} nanos={}", t0.elapsed().as_nanos());
}
