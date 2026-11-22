#!/bin/bash
# Builds the KotlinX compiler (newkotlinc) with Kotlin/Native into compiler/build/ (debug build;
# set NEWKOTLIN_KN_FLAGS=-opt for an optimized compiler binary).
set -e
cd "$(dirname "$0")"
# Kotlin/Native for the host (KONANC overrides; KN_VERSION picks the release under ~/.konan).
KN_VERSION=${KN_VERSION:-2.4.21}
case "$(uname -s)" in Darwin) kn_os=macos;; Linux) kn_os=linux;; *) echo "unsupported host OS: $(uname -s)" >&2; exit 1;; esac
case "$(uname -m)" in arm64|aarch64) kn_arch=aarch64;; x86_64|amd64) kn_arch=x86_64;; *) echo "unsupported host CPU: $(uname -m)" >&2; exit 1;; esac
KN=${KONANC:-$HOME/.konan/kotlin-native-prebuilt-$kn_os-$kn_arch-$KN_VERSION/bin/kotlinc-native}
[ -x "$KN" ] || { echo "Kotlin/Native not found: $KN (install kotlin-native-prebuilt-$kn_os-$kn_arch-$KN_VERSION into ~/.konan or set KONANC)" >&2; exit 1; }
mkdir -p build
"$KN" $(find src -name "*.kt" | sort) -e newkotlin.main ${NEWKOTLIN_KN_FLAGS:-} -o build/newkotlinc
echo "built compiler/build/newkotlinc.kexe"
