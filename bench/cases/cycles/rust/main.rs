// cycles: 20k rings of 100 Rc<RefCell<Node>> nodes (each with a boxed payload); walk 150 steps; the
// ring is broken by hand (Rc cannot collect cycles) and then dropped.
use std::cell::RefCell;
use std::rc::Rc;
use std::time::Instant;
struct Payload { v: i64 }
struct Node { id: i64, next: Option<Rc<RefCell<Node>>>, p: Box<Payload> }
fn ring(seed: i64, size: i64) -> Rc<RefCell<Node>> {
    let first = Rc::new(RefCell::new(Node { id: seed, next: None, p: Box::new(Payload { v: seed * 3 }) }));
    let mut cur = first.clone();
    for i in 1..size {
        let n = Rc::new(RefCell::new(Node { id: seed + i, next: None, p: Box::new(Payload { v: (seed + i) * 3 }) }));
        cur.borrow_mut().next = Some(n.clone());
        cur = n;
    }
    cur.borrow_mut().next = Some(first.clone());
    first
}
fn walk(first: &Rc<RefCell<Node>>, steps: i32) -> i64 {
    let mut s = 0;
    let mut cur = first.clone();
    for _ in 0..steps {
        let next = { let c = cur.borrow(); s += c.id + c.p.v; c.next.clone() };
        match next { Some(n) => cur = n, None => return s }
    }
    s
}
fn main() {
    let t0 = Instant::now();
    let mut checksum: i64 = 0;
    for r in 0..20_000i64 {
        let first = ring(r, 100);
        checksum += walk(&first, 150);
        first.borrow_mut().next.take(); // break the cycle
    }
    println!("checksum={checksum} nanos={}", t0.elapsed().as_nanos());
}
