// bigheap: same workload as Kotlin. Idiomatic safe Rust: nodes are Rc<RefCell<Node>> with a strong `next`
// and a Weak back-pointer to the owning graph; Rc cannot collect cycles, so a replaced graph's ring is
// unwound by hand (iteratively). Acyclic leaves are Option<Box<Leaf>>.
use std::cell::RefCell;
use std::rc::{Rc, Weak};
use std::time::Instant;
struct Payload { v: i64 }
struct Graph { id: i64, head: RefCell<Option<N>> }
struct Node { id: i64, next: Option<N>, owner: Weak<Graph>, p: Box<Payload> }
type N = Rc<RefCell<Node>>;
struct Leaf { v: i64, next: Option<Box<Leaf>> }
fn build(seed: i64, size: i64) -> Rc<Graph> {
    let g = Rc::new(Graph { id: seed, head: RefCell::new(None) });
    let mk = |id, v| Rc::new(RefCell::new(Node { id, next: None, owner: Rc::downgrade(&g), p: Box::new(Payload { v }) }));
    let first = mk(seed * 1000, seed * 3);
    let mut cur = first.clone();
    for i in 1..size {
        let n = mk(seed * 1000 + i, seed * 3 + i);
        cur.borrow_mut().next = Some(n.clone());
        cur = n;
    }
    cur.borrow_mut().next = Some(first.clone());
    *g.head.borrow_mut() = Some(first);
    g
}
fn walk(g: &Graph, steps: i32) -> i64 {
    let mut s = 0;
    let mut cur = g.head.borrow().clone();
    for _ in 0..steps {
        let c = match cur { Some(c) => c, None => return s };
        let next = { let b = c.borrow(); s += b.id + b.p.v + b.owner.upgrade().unwrap().id; b.next.clone() };
        cur = next;
    }
    s
}
fn free(g: Rc<Graph>) {
    let mut cur = g.head.borrow_mut().take();
    while let Some(n) = cur { cur = n.borrow_mut().next.take(); }
}
fn acyclic(seed: i64, n: i64) -> i64 {
    let mut l: Option<Box<Leaf>> = None;
    for i in 0..n { l = Some(Box::new(Leaf { v: seed + i, next: l })); }
    let mut s = 0;
    let mut cur = &l;
    while let Some(c) = cur { s += c.v; cur = &c.next; }
    // iterative drop (chain is short, but keep it symmetric with the ring unwinding)
    while let Some(mut c) = l { l = c.next.take(); }
    s
}
fn main() {
    let t0 = Instant::now();
    let count = 2000usize;
    let mut graphs: Vec<Rc<Graph>> = (0..count as i64).map(|g| build(g, 200)).collect();
    let (mut x, mut checksum, mut seed) = (12345i64, 0i64, count as i64);
    for _ in 0..200 {
        for _ in 0..50 {
            x = x.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            let idx = (((x as u64) >> 33) % count as u64) as usize;
            let g = build(seed, 200);
            free(std::mem::replace(&mut graphs[idx], g));
            checksum += walk(&graphs[idx], 50);
            checksum += acyclic(seed, 100);
            seed += 1;
        }
    }
    for g in &graphs { checksum += walk(g, 10); }
    for g in graphs { free(g); }
    println!("checksum={checksum} nanos={}", t0.elapsed().as_nanos());
}
