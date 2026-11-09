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

const all_gcs = [_][]const u8{ "rc", "perceus", "incremental", "epoch" };

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

    for (all_gcs) |g| {
        if (only) |want| if (!std.mem.eql(u8, want, g)) continue;

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
                .root_source_file = b.path("tests/native/suite.zig"),
                .target = target,
                .optimize = optimize,
                .link_libc = true,
                .imports = &.{
                    .{ .name = "collector", .module = coll },
                    .{ .name = "kxgc", .module = kxgc },
                    .{ .name = "host", .module = host },
                },
            }),
        });
        b.installArtifact(suite);

        const run = b.addRunArtifact(suite);
        run.has_side_effects = true;
        test_step.dependOn(&run.step);

        if (std.mem.eql(u8, g, "epoch")) {
            const inc_suite = b.addExecutable(.{
                .name = "gc-test-epoch-incmajor",
                .root_module = b.createModule(.{
                    .root_source_file = b.path("tests/native/epoch_incmajor.zig"),
                    .target = target,
                    .optimize = optimize,
                    .link_libc = true,
                    .imports = &.{
                        .{ .name = "collector", .module = coll },
                        .{ .name = "kxgc", .module = kxgc },
                        .{ .name = "host", .module = host },
                    },
                }),
            });
            b.installArtifact(inc_suite);
            const inc_run = b.addRunArtifact(inc_suite);
            inc_run.has_side_effects = true;
            test_step.dependOn(&inc_run.step);
        }

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
                .imports = &.{
                    .{ .name = "collector", .module = bcoll },
                    .{ .name = "kxgc", .module = kxgc },
                    .{ .name = "host", .module = host_fast },
                },
            }),
        });
        ops.lto = ops_lto;
        b.installArtifact(ops);

        const run_ops = b.addRunArtifact(ops);
        run_ops.addPassthruArgs();
        run_ops.has_side_effects = true;
        bench_step.dependOn(&run_ops.step);

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
