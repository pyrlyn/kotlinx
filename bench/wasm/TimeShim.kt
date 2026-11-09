// Kotlin/Wasm has no kotlin.system.getTimeNanos (Kotlin/Native only). This file supplies it so the benchmark
// sources compile unchanged with kotlinc-wasm (needs -Xallow-kotlin-package). Not used by newkotlinc.
@file:Suppress("PackageDirectoryMismatch")
package kotlin.system

import kotlin.time.TimeSource

private val origin = TimeSource.Monotonic.markNow()

fun getTimeNanos(): Long = origin.elapsedNow().inWholeNanoseconds
