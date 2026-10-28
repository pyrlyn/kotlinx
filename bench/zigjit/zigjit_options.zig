// Mirrors zig-jit build.zig defaults for ReleaseFast, aarch64 backend only.
pub const x86_64: bool = false;
pub const aarch64: bool = true;
pub const validate: bool = false;
pub const hooks: bool = true;
pub const asmjit_tests: bool = false;
pub const gp_fuzz: bool = false;
pub const bench_counting: bool = false;
