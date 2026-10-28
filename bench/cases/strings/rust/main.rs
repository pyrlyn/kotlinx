// strings: build a 2M-number CSV-like text with a String buffer, then parse it byte by byte.
use std::fmt::Write;
use std::time::Instant;
fn main() {
    let n = 2_000_000;
    let t0 = Instant::now();
    let mut sb = String::new();
    for i in 0..n {
        write!(sb, "{}", i).unwrap();
        sb.push(',');
        if i % 10 == 9 { sb.push_str("x\n"); }
    }
    let s = sb.clone(); // Kotlin's StringBuilder.toString() copies
    let (mut sum, mut cur, mut fields, mut lines) = (0i64, 0i64, 0i64, 0i64);
    for &c in s.as_bytes() {
        if c == b',' { sum += cur; cur = 0; fields += 1; }
        else if c == b'\n' { lines += 1; }
        else if c >= b'0' && c <= b'9' { cur = cur * 10 + (c - b'0') as i64; }
    }
    let checksum = sum + fields * 7 + lines * 13 + s.len() as i64;
    println!("checksum={checksum} nanos={}", t0.elapsed().as_nanos());
}
