//! Bacon & Rajan trial-deletion passes. Port of `common/trial_deletion.inc`.
const host = @import("host");
const obj = @import("object.zig");
const regions = @import("regions.zig");
const builtin = @import("builtin");

pub const CC_THRESHOLD: usize = 10000;

/// The class layout behind a vtable's is-cyclic slot: { u32 n, u32 size, u32 relo, u32 offsets[n] } (n reference
/// fields, object size in bytes, whether the class's relo hook releases anything)
/// (NativeBackend.kt cyclicSlot), or null when the slot is the plain marker 1 and the class is traced by its
/// trav hook (classes with traced list/map fields, --target=js, hand-written vtables in tests).
pub inline fn layout(o: *anyopaque) ?[*]const u32 {
    const c = obj.vtab(o).cyclic;
    if (@intFromPtr(c) == 1) return null;
    return @ptrCast(@alignCast(c));
}

/// Calls visit(self, child) for every traced child of o: straight from the layout when there is one, else
/// through the class's trav hook and the C-callable wrapper cb.
inline fn forChildren(self: anytype, o: *anyopaque, comptime visit: anytype, cb: obj.VisitFn) void {
    if (layout(o)) |lay| {
        const base = @intFromPtr(o);
        var i: u32 = 0;
        while (i < lay[0]) : (i += 1) {
            const slot: *const ?*anyopaque = @ptrFromInt(base + lay[3 + i]);
            visit(self, slot.*);
        }
    } else if (obj.vtab(o).trav) |trav| trav(o, cb);
}
/// Most entries the gray record may hold before collectCycles in perceus.zig falls back to tracing from the roots:
/// 512 KiB keeps native peak RSS where it was without the record on huge live graphs (bench bigheap). Unbounded
/// on wasm (no RSS budget there, and the record is where most of its cycles/bigheap gain is), which also drops the
/// root-tracing fallback from wasm modules.
pub const GRAY_RECORD_MAX: ?usize = if (builtin.cpu.arch.isWasm()) null else (512 << 10) / @sizeOf(usize);
pub const CC_MAX_THRESHOLD: usize = 1 << 18;
pub const CC_PRODUCTIVE_RATIO: usize = 4;

pub fn TrialDeletion(comptime claim_skips_buffered: bool) type {
    return struct {
        const Self = @This();

        work: obj.PtrStack = .{},
        blacken: obj.PtrStack = .{},
        garbage: obj.PtrStack = .{},
        acyclic: obj.PtrStack = .{},
        /// Every object markGray colored gray, in visit order (perceus: scanGray / collectGray walk it instead of
        /// the graph). Empty when record is false; markGray clears record when the set outgrows GRAY_RECORD_MAX.
        gray: obj.PtrStack = .{},
        record: bool = false,
        traced: usize = 0,
        threshold: usize = CC_THRESHOLD,

        /// C visitors close over file-scope stacks; Zig visitors are C pointers.
        pub threadlocal var current: ?*Self = null;

        pub fn activate(self: *Self) void {
            current = self;
        }

        pub fn markGray(self: *Self, root: *anyopaque) void {
            self.markGrayClaim(root);
            while (self.work.len != 0) {
                const o = self.work.pop().?;
                self.traced += 1;
                if (self.record) {
                    if (GRAY_RECORD_MAX != null and self.gray.len >= GRAY_RECORD_MAX.?) {
                        self.record = false;
                        self.gray.len = 0;
                    } else self.gray.push(o);
                }
                regions.visit(o);
                forChildren(self, o, markGrayVisit, markGrayChild);
            }
        }

        inline fn markGrayClaim(self: *Self, o: *anyopaque) void {
            if (obj.color(o) == obj.GRAY) return;
            obj.setColor(o, obj.GRAY);
            self.work.push(o);
        }

        fn markGrayChild(child: ?*anyopaque) callconv(.c) void {
            markGrayVisit(current.?, child);
        }
        inline fn markGrayVisit(self: *Self, child: ?*anyopaque) void {
            if (child) |ch| {
                if (obj.isCyclic(ch)) {
                    obj.header(ch).* -= 1;
                    self.markGrayClaim(ch);
                }
            }
        }

        pub fn scanBlack(self: *Self, o: *anyopaque) void {
            obj.setColor(o, obj.BLACK);
            self.blacken.push(o);
            while (self.blacken.len != 0) {
                const x = self.blacken.pop().?;
                forChildren(self, x, scanBlackVisit, scanBlackChild);
            }
        }

        fn scanBlackChild(child: ?*anyopaque) callconv(.c) void {
            scanBlackVisit(current.?, child);
        }
        inline fn scanBlackVisit(self: *Self, child: ?*anyopaque) void {
            const ch = child orelse return;
            if (!obj.isCyclic(ch)) return;
            obj.header(ch).* += 1;
            if (obj.color(ch) != obj.BLACK) {
                obj.setColor(ch, obj.BLACK);
                self.blacken.push(ch);
            }
        }

        inline fn scanClaim(self: *Self, o: *anyopaque) void {
            if (obj.color(o) != obj.GRAY) return;
            obj.setColor(o, obj.WHITE);
            self.work.push(o);
        }

        pub fn scan(self: *Self, root: *anyopaque) void {
            self.scanClaim(root);
            while (self.work.len != 0) {
                const o = self.work.pop().?;
                if (obj.color(o) != obj.WHITE) continue;
                if (obj.refCount(o) > 0) self.scanBlack(o) else forChildren(self, o, scanVisit, scanChild);
                regions.visit(o);
            }
        }

        fn scanChild(child: ?*anyopaque) callconv(.c) void {
            scanVisit(current.?, child);
        }
        inline fn scanVisit(self: *Self, child: ?*anyopaque) void {
            if (child) |ch| {
                if (obj.isCyclic(ch)) self.scanClaim(ch);
            }
        }

        inline fn collectWhiteClaim(self: *Self, o: *anyopaque) bool {
            if (obj.color(o) != obj.WHITE) return false;
            if (claim_skips_buffered and obj.isBuffered(o)) return false;
            obj.setColor(o, obj.BLACK);
            self.work.push(o);
            return true;
        }

        pub fn collectWhite(self: *Self, root: *anyopaque) void {
            if (!self.collectWhiteClaim(root)) return;
            host.rt_trav_mode = 1; // RT_TRAV_RELEASE
            while (self.work.len != 0) {
                const o = self.work.pop().?;
                forChildren(self, o, collectWhiteVisit, collectWhiteChild);
                regions.visit(o);
                self.garbage.push(o);
            }
            host.rt_trav_mode = 0; // RT_TRAV_SCAN
        }

        fn collectWhiteChild(child: ?*anyopaque) callconv(.c) void {
            collectWhiteVisit(current.?, child);
        }
        inline fn collectWhiteVisit(self: *Self, child: ?*anyopaque) void {
            const ch = child orelse return;
            if (obj.isCyclic(ch)) {
                _ = self.collectWhiteClaim(ch);
            } else {
                self.acyclic.push(ch);
            }
        }

        /// scan over the recorded gray set: every gray object whose count stayed positive after markGray is
        /// externally referenced, so it and everything it reaches is restored (scanBlack); what is still gray
        /// is garbage and turns white. Same coloring as scan() from every root, without re-tracing the garbage.
        pub fn scanGray(self: *Self) void {
            var i: usize = 0;
            while (i < self.gray.len) : (i += 1) {
                const o = self.gray.at(i).?;
                if (obj.color(o) == obj.GRAY and obj.refCount(o) > 0) self.scanBlack(o);
            }
            i = 0;
            while (i < self.gray.len) : (i += 1) {
                const o = self.gray.at(i).?;
                if (obj.color(o) == obj.GRAY) obj.setColor(o, obj.WHITE);
                regions.visit(o);
            }
        }

        /// collectWhite over the recorded gray set, after the caller cleared the buffered bit of every root:
        /// each white object becomes garbage and queues its acyclic children; its cyclic children are either
        /// garbage themselves (queued by their own entry) or live.
        pub fn collectGray(self: *Self) void {
            host.rt_trav_mode = 1; // RT_TRAV_RELEASE
            var i: usize = 0;
            while (i < self.gray.len) : (i += 1) {
                const o = self.gray.at(i).?;
                if (obj.color(o) != obj.WHITE) continue;
                obj.setColor(o, obj.BLACK);
                forChildren(self, o, acyclicVisit, acyclicChild);
                regions.visit(o);
                self.garbage.push(o);
            }
            host.rt_trav_mode = 0; // RT_TRAV_SCAN
            self.gray.len = 0;
        }

        fn acyclicChild(child: ?*anyopaque) callconv(.c) void {
            acyclicVisit(current.?, child);
        }
        inline fn acyclicVisit(self: *Self, child: ?*anyopaque) void {
            const ch = child orelse return;
            if (!obj.isCyclic(ch)) self.acyclic.push(ch);
        }

        pub fn releaseAcyclicChildren(self: *Self) void {
            while (self.acyclic.len != 0) {
                const child = self.acyclic.pop().?;
                if (obj.vtab(child).rel) |rel| rel(child);
            }
        }

        pub fn adaptThreshold(self: *Self, reclaimed: usize) void {
            if (reclaimed * CC_PRODUCTIVE_RATIO < self.traced) {
                if (self.threshold < CC_MAX_THRESHOLD) self.threshold *= 2;
            } else {
                self.threshold = CC_THRESHOLD;
            }
        }
    };
}
