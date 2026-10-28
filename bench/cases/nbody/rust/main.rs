// nbody: 5 bodies, 1M steps of pairwise gravity in f64; checksum = (energy * 1e9) as i64.
use std::time::Instant;
struct Body { x: f64, y: f64, z: f64, vx: f64, vy: f64, vz: f64, m: f64 }
fn advance(bs: &mut Vec<Box<Body>>, dt: f64) {
    let n = bs.len();
    for i in 0..n {
        for j in i + 1..n {
            let (l, r) = bs.split_at_mut(j);
            let a = &mut l[i]; let b = &mut r[0];
            let dx = a.x - b.x; let dy = a.y - b.y; let dz = a.z - b.z;
            let d2 = dx * dx + dy * dy + dz * dz;
            let mag = dt / (d2 * d2.sqrt());
            a.vx -= dx * b.m * mag; a.vy -= dy * b.m * mag; a.vz -= dz * b.m * mag;
            b.vx += dx * a.m * mag; b.vy += dy * a.m * mag; b.vz += dz * a.m * mag;
        }
    }
    for b in bs.iter_mut() { b.x += dt * b.vx; b.y += dt * b.vy; b.z += dt * b.vz; }
}
fn energy(bs: &Vec<Box<Body>>) -> f64 {
    let mut e = 0.0;
    let n = bs.len();
    for i in 0..n {
        let a = &bs[i];
        e += 0.5 * a.m * (a.vx * a.vx + a.vy * a.vy + a.vz * a.vz);
        for j in i + 1..n {
            let b = &bs[j];
            let dx = a.x - b.x; let dy = a.y - b.y; let dz = a.z - b.z;
            e -= a.m * b.m / (dx * dx + dy * dy + dz * dz).sqrt();
        }
    }
    e
}
fn main() {
    let mut bs: Vec<Box<Body>> = Vec::new();
    for i in 0..5i32 {
        let d = i as f64;
        bs.push(Box::new(Body { x: d * 3.0, y: ((i * 2 % 5) as f64) * 1.5, z: ((i % 3) as f64) - 1.0,
            vx: 0.01 * d, vy: -0.02 * d, vz: 0.005, m: 1.0 + d }));
    }
    let t0 = Instant::now();
    for _ in 0..1_000_000 { advance(&mut bs, 0.001); }
    let checksum = (energy(&bs) * 1e9) as i64;
    println!("checksum={checksum} nanos={}", t0.elapsed().as_nanos());
}
