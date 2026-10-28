zig build-exe -OReleaseFast --dep zigjit -Mroot=main.zig --dep zigjit_options -Mzigjit=$HOME/Grok/zig-jit/src/zigjit.zig -Mzigjit_options=zigjit_options.zig -lc -femit-bin=bench_zigjit
