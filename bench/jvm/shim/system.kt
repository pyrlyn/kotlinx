// Kotlin/JVM row of bench/jvm/run.sh: the Kotlin/Native-only `kotlin.system.getTimeNanos()` the bench sources
// call, on the JVM clock. Compiled with -Xallow-kotlin-package.
package kotlin.system

fun getTimeNanos(): Long = System.nanoTime()
