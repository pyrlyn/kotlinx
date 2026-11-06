//! Umbrella native build for KotlinX GC after submodule removal.
//!
//! - C collectors: vendored under `c/` (pyrlyn/gc@6acde64), compiled as C sources with link_libc.
//! - Zig collectors: vendored under `zig/` (pyrlyn/gc@cf79057), consumed as modules via @import.
//!
//! This does not rewrite the Kotlin/Native `newkotlinc` compiler. It provides:
//! 1) a Zig module graph consumers can `@import("kxgc")` / `@import("host")` / `@import("epoch")`,
//! 2) a C smoke that links real `compiler/runtime/runtime.c` + `c/epoch/gc.c`,
//! 3) a Zig smoke in a separate process (host adapter, no duplicate runtime exports).
//!
//! Requires official Zig 0.17.1.
const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    // --- Zig modules (real collector sources; unmodified under zig/src) ---
    const kxgc = b.addModule("kxgc", .{
        .root_source_file = b.path("zig/src/kxgc.zig"),
    });
    const host = b.addModule("host", .{
        .root_source_file = b.path("zig/src/host.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    const epoch = b.addModule("epoch", .{
        .root_source_file = b.path("zig/src/epoch.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    epoch.addImport("kxgc", kxgc);
    epoch.addImport("host", host);

    // Also expose other collectors the same way (separate processes choose one).
    inline for (.{ "rc", "perceus", "incremental" }) |name| {
        const mod = b.addModule(name, .{
            .root_source_file = b.path(b.fmt("zig/src/{s}.zig", .{name})),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        });
        mod.addImport("kxgc", kxgc);
        mod.addImport("host", host);
    }

    // Path dependency package (zig/build.zig.zon) stays available for `cd zig && zig build test`.
    _ = b.dependency("kxgc_zig", .{
        .target = target,
        .optimize = optimize,
    });

    // --- Zig smoke (Debug / ReleaseFast via -Doptimize=...) ---
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
    const zig_step = b.step("zig-smoke", "Run Zig epoch smoke (@import kxgc/host/epoch)");
    zig_step.dependOn(&run_zig.step);

    // --- C smoke: compile C collector sources + real KotlinX runtime.c ---
    const runtime_c = b.path("../../compiler/runtime/runtime.c");
    const c_flags = [_][]const u8{
        "-std=c11",
        "-DRT_PLAIN_MALLOC",
        "-Wno-unused-parameter",
    };
    const c_smoke = b.addExecutable(.{
        .name = "c-epoch-smoke",
        .root_module = b.createModule(.{
            .root_source_file = b.path("smoke/c_entry.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        }),
    });
    c_smoke.root_module.addCSourceFile(.{ .file = b.path("smoke/c_epoch_smoke.c"), .flags = &c_flags });
    c_smoke.root_module.addCSourceFile(.{ .file = b.path("c/epoch/gc.c"), .flags = &c_flags });
    c_smoke.root_module.addCSourceFile(.{ .file = runtime_c, .flags = &c_flags });
    c_smoke.root_module.addIncludePath(b.path("c"));
    b.installArtifact(c_smoke);

    const run_c = b.addRunArtifact(c_smoke);
    run_c.has_side_effects = true;
    const c_step = b.step("c-smoke", "Run C epoch smoke (runtime.c + c/epoch/gc.c)");
    c_step.dependOn(&run_c.step);

    const smoke = b.step("smoke", "Run C and Zig epoch smokes (separate processes)");
    smoke.dependOn(c_step);
    smoke.dependOn(zig_step);
}
