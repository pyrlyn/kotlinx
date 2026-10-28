// dispatch: trait-object calls over 1000 mixed shapes, 20k rounds.
use std::time::Instant;
trait Shape { fn area(&self) -> i64; fn scale(&self, k: i64) -> i64; }
struct Square { s: i64 }
struct Rect { w: i64, h: i64 }
struct Tri { b: i64, h: i64 }
impl Shape for Square { fn area(&self) -> i64 { self.s * self.s } fn scale(&self, k: i64) -> i64 { self.s * k } }
impl Shape for Rect { fn area(&self) -> i64 { self.w * self.h } fn scale(&self, k: i64) -> i64 { (self.w + self.h) * k } }
impl Shape for Tri { fn area(&self) -> i64 { self.b * self.h / 2 } fn scale(&self, k: i64) -> i64 { self.b * k - self.h } }
fn main() {
    let mut shapes: Vec<Box<dyn Shape>> = Vec::new();
    for i in 0..1000i64 {
        match i % 3 {
            0 => shapes.push(Box::new(Square { s: i })),
            1 => shapes.push(Box::new(Rect { w: i, h: i + 1 })),
            _ => shapes.push(Box::new(Tri { b: i, h: i + 2 })),
        }
    }
    let shapes = std::hint::black_box(shapes);
    let t0 = Instant::now();
    let mut sum = 0i64;
    for r in 0..20_000i64 {
        let k = r & 7;
        for s in &shapes { sum += s.area() + s.scale(k); }
    }
    println!("checksum={sum} nanos={}", t0.elapsed().as_nanos());
}
