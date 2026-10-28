// lru: a real-world cyclic structure - an LRU cache (doubly linked list + key index), same workload as Kotlin.
// Idiomatic safe Rust: next = Rc<RefCell<Entry>> (strong), prev = Weak (breaks the cycle), boxed value.
use std::cell::RefCell;
use std::rc::{Rc, Weak};
use std::time::Instant;
struct Value { v: i64 }
struct Entry { key: usize, value: Box<Value>, prev: Option<Weak<RefCell<Entry>>>, next: Option<Rc<RefCell<Entry>>> }
type E = Rc<RefCell<Entry>>;
struct Lru { capacity: usize, index: Vec<Option<E>>, head: Option<E>, tail: Option<E>, size: usize }
impl Lru {
    fn unlink(&mut self, e: &E) {
        let (p, n) = { let mut b = e.borrow_mut(); (b.prev.take().and_then(|w| w.upgrade()), b.next.take()) };
        match &p { None => self.head = n.clone(), Some(p) => p.borrow_mut().next = n.clone() }
        match &n { None => self.tail = p, Some(n) => n.borrow_mut().prev = p.as_ref().map(Rc::downgrade) }
    }
    fn push_front(&mut self, e: E) {
        match self.head.take() {
            None => self.tail = Some(e.clone()),
            Some(h) => { h.borrow_mut().prev = Some(Rc::downgrade(&e)); e.borrow_mut().next = Some(h); }
        }
        self.head = Some(e);
    }
    fn get(&mut self, key: usize) -> i64 {
        if let Some(e) = self.index[key].clone() {
            if e.borrow().prev.is_some() { self.unlink(&e); self.push_front(e.clone()); }
            return e.borrow().value.v;
        }
        let e = Rc::new(RefCell::new(Entry { key, value: Box::new(Value { v: key as i64 * 7 + 1 }), prev: None, next: None }));
        self.index[key] = Some(e.clone());
        self.push_front(e);
        self.size += 1;
        if self.size > self.capacity {
            let t = self.tail.clone().unwrap();
            self.unlink(&t);
            self.index[t.borrow().key] = None;
            self.size -= 1;
        }
        -1
    }
}
fn main() {
    let t0 = Instant::now();
    let keys = 200_000;
    let mut cache = Lru { capacity: 50_000, index: vec![None; keys], head: None, tail: None, size: 0 };
    let (mut x, mut hits, mut sum) = (12345i64, 0i64, 0i64);
    for _ in 0..3_000_000 {
        x = x.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        let r = (((x as u64) >> 33) % 1_000_000) as usize;
        let key = if r < 800_000 { r % 40_000 } else { 40_000 + r % 160_000 };
        let v = cache.get(key);
        if v >= 0 { hits += 1; sum += v; }
    }
    let checksum = sum + hits * 31 + cache.size as i64;
    // Dropping a long strong `next` chain recursively would overflow the stack: unwind it iteratively.
    cache.index.clear(); cache.tail = None;
    let mut cur = cache.head.take();
    while let Some(e) = cur { cur = e.borrow_mut().next.take(); }
    println!("checksum={checksum} nanos={}", t0.elapsed().as_nanos());
}
