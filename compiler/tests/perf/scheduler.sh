#!/bin/bash
# Coroutine scheduler timings: N jobs x 20 yields (ready queue) and N jobs x 5 delays (timer queue).
# Usage: compiler/tests/perf/scheduler.sh [nkc] [N...]   (default: compiler/build/newkotlinc.kexe, 2000 4000 8000 16000)
# Prints the best of 3 runs per program, in ms.
set -e
cd "$(dirname "$0")/../../.."
NKC=${1:-compiler/build/newkotlinc.kexe}; shift || true
SIZES=${*:-2000 4000 8000 16000}
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
ms() { local best=999999 s e t; for _ in 1 2 3; do s=$(date +%s%N); "$1" > /dev/null; e=$(date +%s%N); t=$(( (e - s) / 1000000 )); [ $t -lt $best ] && best=$t; done; echo $best; }
for n in $SIZES; do
  cat > "$W/yield_$n.kt" <<KT
import kotlinx.coroutines.*
fun main() = runBlocking {
    var sum = 0L
    val jobs = ArrayList<Job>()
    for (i in 0 until $n) jobs.add(launch { for (k in 0 until 20) { sum += k; yield() } })
    for (j in jobs) j.join()
    println(sum)
}
KT
  cat > "$W/delay_$n.kt" <<KT
import kotlinx.coroutines.*
fun main() = runBlocking {
    var sum = 0L
    val jobs = ArrayList<Job>()
    for (i in 0 until $n) jobs.add(launch { for (k in 0 until 5) { delay(((i * 7919 + k * 104729) % 1000).toLong()); sum += 1 } })
    for (j in jobs) j.join()
    println(sum)
}
KT
  for k in yield delay; do
    "$NKC" ${RUNTIME_FLAGS:-} "$W/${k}_$n.kt" -o "$W/${k}_$n"
    echo "${k} N=$n $(ms "$W/${k}_$n") ms"
  done
done
