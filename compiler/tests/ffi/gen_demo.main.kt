// Uses the bindings kxbindgen generates from gen_demo.h (prepended by compiler/test.sh).
fun main() {
    println(demo_add(DEMO_VERSION, 39))
    println(demo_mix(1.5, 4L, true))
    val p = demo_pair_new(DEMO_BIG, 5L)
    println(demo_pair_sum(p))
    println(DEMO_FAST + DEMO_SLOW)
}
