//! Umbrella native build for KotlinX GC (vendored path dependencies; no git submodule).
//!
//! - C collectors: `c/` (pyrlyn/gc@6acde64) via `@import("kxgc_c")` (@cImport of kxgc.h) + .c sources.
//! - Zig collectors: `zig/` path dep (pyrlyn/gc@cf79057 + runtime_host adapter); modules via
//!   `b.dependency("kxgc_zig").module(...)`.
//! - KotlinX programs: `zig build gc-libs` in `zig/` produces `libgc-<name>.a` linked by newkotlinc
//!   when `--gc-runtime=zig` (default). `compiler/runtime/runtime.c` stays the shared runtime.
//!
//! Requires official Zig 0.17.1.
const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    // Path dependency: use exported modules from the vendored Zig package (not only b.path).
    const gcdep = b.dependency("kxgc_zig", .{
        .target = target,
        .optimize = optimize,
    });
    const kxgc = gcdep.module("kxgc");
    const host = gcdep.module("host");
    const epoch = gcdep.module("epoch");
    _ = gcdep.module("rc");
    _ = gcdep.module("perceus");
    _ = gcdep.module("incremental");
    _ = gcdep.module("runtime_host");

    // C ABI module: Zig 0.17 translate-c of vendored kxgc.h (replaces removed @cImport).
    const kxgc_c_tc = b.addTranslateC(.{
        .root_source_file = b.path("smoke/kxgc_c_root.h"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    kxgc_c_tc.addIncludePath(b.path("c"));
    const kxgc_c = kxgc_c_tc.addModule("kxgc_c");

    // --- Zig smoke: @import kxgc/host/epoch from the path dependency ---
    const zig_smoke = b.addExecutable(.{
        .name = "zig-epoch-smoke",
        .root_module = b.createModule(.{
            .root_source_file = b.path("smoke/zig_epoch_smoke.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
            .imports = &.{
                .{ .name = "kxgc", .module = kxgc },
                .{ .name = "host", .module = host },
                .{ .name = "epoch", .module = epoch },
            },
        }),
    });
    b.installArtifact(zig_smoke);
    const run_zig = b.addRunArtifact(zig_smoke);
    run_zig.has_side_effects = true;
    const zig_step = b.step("zig-smoke", "Run Zig epoch smoke (@import path-dep modules)");
    zig_step.dependOn(&run_zig.step);

    // --- C smoke: @import("kxgc_c") + link runtime.c + c/epoch/gc.c ---
    const runtime_c = b.path("../../compiler/runtime/runtime.c");
    const c_flags = [_][]const u8{ "-std=c11", "-DRT_PLAIN_MALLOC", "-Wno-unused-parameter" };
    const c_smoke = b.addExecutable(.{
        .name = "c-epoch-smoke",
        .root_module = b.createModule(.{
            .root_source_file = b.path("smoke/c_entry.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
            .imports = &.{
                .{ .name = "kxgc_c", .module = kxgc_c },
            },
        }),
    });
    c_smoke.root_module.addCSourceFile(.{ .file = b.path("smoke/c_epoch_smoke.c"), .flags = &c_flags });
    c_smoke.root_module.addCSourceFile(.{ .file = b.path("c/epoch/gc.c"), .flags = &c_flags });
    c_smoke.root_module.addCSourceFile(.{ .file = runtime_c, .flags = &c_flags });
    c_smoke.root_module.addIncludePath(b.path("c"));
    b.installArtifact(c_smoke);
    const run_c = b.addRunArtifact(c_smoke);
    run_c.has_side_effects = true;
    const c_step = b.step("c-smoke", "Run C epoch smoke (@import kxgc_c + runtime.c + gc.c)");
    c_step.dependOn(&run_c.step);

    // Zig-linked C-API check: Zig driver links libgc-epoch.a + runtime.c and uses @import("kxgc_c").
    const zig_rt_smoke = b.addExecutable(.{
        .name = "zig-runtime-epoch-smoke",
        .root_module = b.createModule(.{
            .root_source_file = b.path("smoke/zig_runtime_epoch_smoke.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
            .imports = &.{
                .{ .name = "kxgc_c", .module = kxgc_c },
            },
        }),
    });
    zig_rt_smoke.root_module.addCSourceFile(.{ .file = runtime_c, .flags = &c_flags });
    zig_rt_smoke.root_module.addIncludePath(b.path("c"));
    zig_rt_smoke.root_module.addObjectFile(b.path("zig/zig-out/lib/libgc-epoch.a"));
    b.installArtifact(zig_rt_smoke);
    const run_zrt = b.addRunArtifact(zig_rt_smoke);
    run_zrt.has_side_effects = true;
    const zrt_step = b.step("zig-runtime-smoke", "Link Zig libgc-epoch.a with runtime.c; @import(kxgc_c)");
    zrt_step.dependOn(&run_zrt.step);

    const smoke = b.step("smoke", "C smoke + Zig module smoke (+ zig-runtime if libs present)");
    smoke.dependOn(c_step);
    smoke.dependOn(zig_step);
}
