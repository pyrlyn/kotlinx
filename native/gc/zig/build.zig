//! Native Zig build for the KotlinX GC port.
//! Requires official Zig 0.17.1 (https://ziglang.org/download/ dated 2026-11-02).
//! Do not use 0.18.0-dev (master).
//!
//!   zig build                        # compile every collector's test + bench binaries
//!   zig build test                   # run native regression for all collectors
//!   zig build test -Dgc=perceus      # one collector
//!   zig build bench                  # optimized ops benches (ReleaseFast; LTO on ELF/COFF only)
//!   zig build gc-libs                # static libs for KotlinX (runtime_host; link with runtime.c)
const std = @import("std");

const all_gcs = [_][]const u8{ "rc", "perceus", "incremental", "epoch", "compact" };

pub fn configureCollector(b: *std.Build, lib: *std.Build.Step.Compile, with_leakcheck: bool) void {
    const options = b.addOptions();
    options.addOption(bool, "leakcheck", with_leakcheck);
    options.addOption(bool, "pool", false);
    options.addOption(bool, "plain_malloc", false);
    const host_options = b.createModule(.{ .root_source_file = b.path("src/lib/host_options.zig") });
    host_options.addImport("gc_options", options.createModule());
    const leakcheck = b.createModule(.{ .root_source_file = b.path("src/lib/leakcheck.zig") });
    leakcheck.addImport("host_options", host_options);
    lib.root_module.addImport("leakcheck", leakcheck);
    lib.linkLibC();
}

pub fn build(b: *std.Build) void {
    const with_leakcheck = b.option(bool, "leakcheck", "Enable C runtime leak-check ABI hooks") orelse false;
    const with_pool = b.option(bool, "pool", "Enable pooled RT allocations") orelse false;
    const with_plain_malloc = b.option(bool, "plain-malloc", "Use malloc for tracing allocations") orelse false;
    const options = b.addOptions();
    options.addOption(bool, "leakcheck", with_leakcheck);
    options.addOption(bool, "pool", with_pool);
    options.addOption(bool, "plain_malloc", with_plain_malloc);
    const host_options = b.createModule(.{ .root_source_file = b.path("src/lib/host_options.zig") });
    host_options.addImport("gc_options", options.createModule());
    const leakcheck = b.createModule(.{ .root_source_file = b.path("src/lib/leakcheck.zig") });
    leakcheck.addImport("host_options", host_options);
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const only = b.option([]const u8, "gc", "Collector to build/test (default: all)");
    const bench_options = b.addOptions();
    bench_options.addOption(usize, "object_size", b.option(usize, "bench-object-size", "Ops object bytes (minimum 40; default 40)") orelse 40);
    bench_options.addOption(usize, "live_count", b.option(usize, "bench-live", "Ops live objects (power of two; default 65536)") orelse 65536);

    const kxgc = b.addModule("kxgc", .{ .root_source_file = b.path("src/kxgc.zig") });
    const host = b.addModule("host", .{
        .root_source_file = b.path("src/host.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    host.addImport("host_options", host_options);
    // Adapter for linking into KotlinX programs that already provide runtime.c symbols.
    const runtime_host = b.addModule("runtime_host", .{
        .root_source_file = b.path("src/runtime_host.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    runtime_host.addImport("host_options", host_options);

    // Export each collector as a path-dependency module (standalone host for tests/benches).
    inline for (all_gcs) |g| {
        const mod = b.addModule(g, .{
            .root_source_file = b.path(b.fmt("src/{s}.zig", .{g})),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        });
        mod.addImport("kxgc", kxgc);
        mod.addImport("host", host);
        mod.addImport("leakcheck", leakcheck);
    }

    const test_step = b.step("test", "Run native Zig GC regression for selected collectors");
    const bench_step = b.step("bench", "Build and run native ops benches (ReleaseFast)");
    const libs_step = b.step("gc-libs", "Install static libs (runtime_host) for KotlinX --gc-runtime=zig");
    const bc_step = b.step("gc-bc", "Install LLVM bitcode of the runtime_host libs (KotlinX --target=js)");
    const bench_opt: std.builtin.Optimize = if (optimize == .debug) .fast else optimize;
    // Zig 0.17.1: LLVM LTO needs LLD. The self-hosted Mach-O linker cannot do LLVM LTO.
    const ops_lto: std.zig.LtoMode = switch (target.result.ofmt) {
        .elf, .coff, .wasm => .full,
        else => .none,
    };
    const host_fast = b.createModule(.{
        .root_source_file = b.path("src/host.zig"),
        .target = target,
        .optimize = bench_opt,
        .link_libc = true,
    });
    host_fast.addImport("host_options", host_options);

    // Libs always ReleaseFast for program linking (Darwin: no LTO on the archive).
    const lib_opt: std.builtin.Optimize = .fast;
    // The gc-bc bitcode (--target=js) is ReleaseSmall: JS size matters there, and the collector's hot helpers are
    // `inline fn`, so it runs as fast as ReleaseFast.
    const bc_opt: std.builtin.Optimize = .small;
    const kxgc_lib = b.createModule(.{ .root_source_file = b.path("src/kxgc.zig") });
    const runtime_host_lib = b.createModule(.{
        .root_source_file = b.path("src/runtime_host.zig"),
        .target = target,
        .optimize = lib_opt,
        .link_libc = true,
    });
    kxgc_lib.addImport("host_options", host_options);
    runtime_host_lib.addImport("host_options", host_options);

    // wasm32-wasi (Node/Bun/Deno/browser via scripts/wasm/run.mjs) is single-threaded: no pthreads,
    // so `epoch` (background collector threads) is not built there.
    const is_wasm = target.result.cpu.arch.isWasm();
    // The shadow stack sits at the bottom of linear memory, so its whole size is resident memory of
    // every wasm instance. Zig's default is 16 MiB; the collectors use explicit work stacks. Optimized
    // tests and benches peak under 32 KiB of it, the Debug test suite near 180 KiB (suite.zig `objRel`
    // recurses down a 1000-object chain), so keep at least 256 KiB for Debug. Overflowing it traps
    // (stack-first layout).
    const wasm_stack = b.option(u64, "wasm-stack-size", "wasm shadow stack bytes (default 1 MiB)") orelse 1 << 20;
    // DWARF is ~94% of an optimized wasm module (1.2 MiB of 1.3 MiB) and engines keep the module bytes.
    const strip_opt = b.option(bool, "strip", "Strip debug info (default: optimized wasm builds)");

    for (all_gcs) |g| {
        if (only) |want| if (!std.mem.eql(u8, want, g)) continue;
        if (is_wasm and std.mem.eql(u8, g, "epoch")) continue;

        const coll = b.createModule(.{
            .root_source_file = b.path(b.fmt("src/{s}.zig", .{g})),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        });
        coll.addImport("kxgc", kxgc);
        coll.addImport("host", host);
        coll.addImport("leakcheck", leakcheck);
        coll.addImport("host_options", host_options);

        const suite = b.addExecutable(.{
            .name = b.fmt("gc-test-{s}", .{g}),
            .root_module = b.createModule(.{
                // compact keeps no statistics, which the shared suite checks: it has its own tests.
                .root_source_file = b.path(if (std.mem.eql(u8, g, "compact")) "tests/native/compact.zig" else "tests/native/suite.zig"),
                .target = target,
                .optimize = optimize,
                .link_libc = true,
                .strip = if (strip_opt) |v| v else if (is_wasm and optimize != .debug) true else null,
                .imports = &.{
                    .{ .name = "collector", .module = coll },
                    .{ .name = "kxgc", .module = kxgc },
                    .{ .name = "host", .module = host },
                    .{ .name = "host_options", .module = host_options },
                },
            }),
        });
        if (is_wasm) suite.stack_size = wasm_stack;
        b.installArtifact(suite);

        const run = b.addRunArtifact(suite);
        run.has_side_effects = true;
        test_step.dependOn(&run.step);

        const bcoll = b.createModule(.{
            .root_source_file = b.path(b.fmt("src/{s}.zig", .{g})),
            .target = target,
            .optimize = bench_opt,
            .link_libc = true,
        });
        bcoll.addImport("kxgc", kxgc);
        bcoll.addImport("host", host_fast);
        bcoll.addImport("leakcheck", leakcheck);
        bcoll.addImport("host_options", host_options);

        const ops = b.addExecutable(.{
            .name = b.fmt("gc-ops-{s}", .{g}),
            .root_module = b.createModule(.{
                .root_source_file = b.path("bench/native/ops.zig"),
                .target = target,
                .optimize = bench_opt,
                .link_libc = true,
                .strip = if (strip_opt) |v| v else if (is_wasm) true else null,
                .imports = &.{
                    .{ .name = "collector", .module = bcoll },
                    .{ .name = "kxgc", .module = kxgc },
                    .{ .name = "host", .module = host_fast },
                },
            }),
        });
        ops.lto = ops_lto;
        if (is_wasm) ops.stack_size = wasm_stack;
        ops.root_module.addOptions("bench_options", bench_options);
        b.installArtifact(ops);

        const run_ops = b.addRunArtifact(ops);
        run_ops.addPassthruArgs();
        run_ops.has_side_effects = true;
        bench_step.dependOn(&run_ops.step);

        // Cycle-collector workload (perceus / incremental; epoch and rc print "skipped"), and size-class
        // phases (peak memory when memory freed by one size class must serve the next), and random churn
        // (free and replace objects in random order), and mass death under deferred RC (epoch; the others
        // print "skipped").
        for ([_][]const u8{ "cycles", "phases", "churn", "drop" }) |bench| {
            const exe = b.addExecutable(.{
                .name = b.fmt("gc-{s}-{s}", .{ bench, g }),
                .root_module = b.createModule(.{
                    .root_source_file = b.path(b.fmt("bench/native/{s}.zig", .{bench})),
                    .target = target,
                    .optimize = bench_opt,
                    .link_libc = true,
                    .strip = if (strip_opt) |v| v else if (is_wasm) true else null,
                    .imports = &.{
                        .{ .name = "collector", .module = bcoll },
                        .{ .name = "kxgc", .module = kxgc },
                        .{ .name = "host", .module = host_fast },
                    },
                }),
            });
            exe.lto = ops_lto;
            if (is_wasm) exe.stack_size = wasm_stack;
            b.installArtifact(exe);
        }

        // Static library for KotlinX: collector + runtime_host (extern → runtime.c).
        const coll_rt = b.createModule(.{
            .root_source_file = b.path(b.fmt("src/{s}.zig", .{g})),
            .target = target,
            .optimize = lib_opt,
            .link_libc = true,
        });
        coll_rt.addImport("kxgc", kxgc_lib);
        coll_rt.addImport("host", runtime_host_lib);
        coll_rt.addImport("leakcheck", leakcheck);
        coll_rt.addImport("host_options", host_options);

        const lib = b.addLibrary(.{
            .name = b.fmt("gc-{s}", .{g}),
            .linkage = .static,
            .root_module = coll_rt,
        });
        // Keep the archive free of Zig LTO bitcode so clang -flto can link it with runtime.c.
        lib.lto = .none;
        const install_lib = b.addInstallArtifact(lib, .{});
        libs_step.dependOn(&install_lib.step);
        // Default install also places libs when `zig build` / `zig build install` runs.
        b.getInstallStep().dependOn(&install_lib.step);

        // LLVM bitcode of the same library for newkotlinc --target=js, which links it with the program's IR and
        // translates the whole module to JavaScript (single-threaded collectors only).
        const bc_mod = b.createModule(.{
            .root_source_file = b.path(b.fmt("src/{s}.zig", .{g})),
            .target = target,
            .optimize = bc_opt,
            .link_libc = true,
        });
        bc_mod.addImport("kxgc", kxgc_lib);
        bc_mod.addImport("host", runtime_host_lib);
        bc_mod.addImport("leakcheck", leakcheck);
        bc_mod.addImport("host_options", host_options);
        const bc_lib = b.addLibrary(.{ .name = b.fmt("gc-{s}-bc", .{g}), .linkage = .static, .root_module = bc_mod });
        bc_lib.lto = .none;
        const bc = b.addInstallFile(bc_lib.getEmittedLlvmBc(), b.fmt("lib/libgc-{s}.bc", .{g}));
        bc_step.dependOn(&bc.step);
    }

}
