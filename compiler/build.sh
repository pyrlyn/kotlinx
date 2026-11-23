#!/bin/bash
# Builds the ZKotlin compiler (newkotlinc) with Kotlin/Native into compiler/build/ (debug build;
# set NEWKOTLIN_KN_FLAGS=-opt for an optimized compiler binary). Warnings are errors (-Werror).
set -e
cd "$(dirname "$0")"
# Kotlin/Native for the host (KONANC overrides; KN_VERSION picks the release under ~/.konan).
KN_VERSION=${KN_VERSION:-2.4.21}
case "$(uname -s)" in Darwin) kn_os=macos;; Linux) kn_os=linux;; *) echo "unsupported host OS: $(uname -s)" >&2; exit 1;; esac
case "$(uname -m)" in arm64|aarch64) kn_arch=aarch64;; x86_64|amd64) kn_arch=x86_64;; *) echo "unsupported host CPU: $(uname -m)" >&2; exit 1;; esac
KN=${KONANC:-$HOME/.konan/kotlin-native-prebuilt-$kn_os-$kn_arch-$KN_VERSION/bin/kotlinc-native}
[ -x "$KN" ] || { echo "Kotlin/Native not found: $KN (install kotlin-native-prebuilt-$kn_os-$kn_arch-$KN_VERSION into ~/.konan or set KONANC)" >&2; exit 1; }
mkdir -p build
# interop/kxproc.def: posix_spawn and the environment block for the driver's process runner (src/driver/Process.kt);
# rebuilt when the .def changes. CINTEROP overrides the cinterop next to kotlinc-native.
case "$KN" in */*) CINTEROP=${CINTEROP:-$(dirname "$KN")/cinterop};; *) CINTEROP=${CINTEROP:-cinterop};; esac
if [ ! -f build/kxproc.klib ] || [ interop/kxproc.def -nt build/kxproc.klib ]; then
  "$CINTEROP" -def interop/kxproc.def -o build/kxproc >/dev/null
fi
"$KN" $(find src -name "*.kt" | sort) -l build/kxproc.klib -e newkotlin.main -Werror ${NEWKOTLIN_KN_FLAGS:-} -o build/newkotlinc
echo "built compiler/build/newkotlinc.kexe"
