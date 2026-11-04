const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const lib = b.addModule("pitx", .{ .root_source_file = b.path("src/pitx.zig"), .target = target, .optimize = optimize });

    const exe = b.addExecutable(.{ .name = "pitx", .root_module = b.createModule(.{
        .root_source_file = b.path("src/main.zig"),
        .target = target,
        .optimize = optimize,
        .imports = &.{.{ .name = "pitx", .module = lib }},
    }) });
    b.installArtifact(exe);
    const run = b.addRunArtifact(exe);
    b.step("run", "Run pitx").dependOn(&run.step);

    const tests = b.addTest(.{ .root_module = b.createModule(.{
        .root_source_file = b.path("tests/pitx_test.zig"),
        .target = target,
        .optimize = optimize,
        .imports = &.{.{ .name = "pitx", .module = lib }},
    }) });
    const run_tests = b.addRunArtifact(tests);
    run_tests.setCwd(b.path("."));
    b.step("test", "Run pitx tests (needs llvm-as and lli from the repo's mise LLVM tools)").dependOn(&run_tests.step);
}
