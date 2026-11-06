/* Scoped smoke: link KotlinX runtime.c + vendored C epoch collector.
 * Immediate acyclic rt_alloc/rt_free + one traced free via VTab.freemem.
 * Entry is c_epoch_smoke_main so the Zig driver owns process main.
 */
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include "kxgc.h"

static int fails;
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); fails++; } } while (0)

typedef struct Obj { uint64_t hdr; KxVTab *vt; int64_t val; } Obj;
static int64_t freed;

static void obj_free(void *p) { freed++; rt_free_trace(p, sizeof(Obj)); }
static void obj_trav(void *p, kx_visit_fn f) { (void)p; (void)f; }
static void obj_relo(void *p) { (void)p; }
static void obj_rel(void *p) {
    if (!p) return;
    Obj *o = p;
    uint64_t c1 = o->hdr - 1;
    if ((c1 & KX_CNT_MASK) != 0) { o->hdr = c1; return; }
    o->hdr = c1;
    obj_relo(p);
    obj_free(p);
}
static KxVTab vt = { obj_rel, obj_trav, obj_relo, obj_free, NULL };

int c_epoch_smoke_main(void) {
    printf("c-smoke gc=%s\n", rt_gc_name());
    for (int i = 0; i < 10000; i++) {
        int64_t *p = rt_alloc(48);
        CHECK(p != NULL);
        p[0] = 1;
        p[5] = i;
        CHECK(p[5] == i);
        rt_free(p, 48);
    }
    freed = 0;
    Obj *o = rt_alloc_trace(sizeof(Obj));
    CHECK(o != NULL);
    o->hdr = 1;
    o->vt = &vt;
    o->val = 42;
    obj_rel(o);
    CHECK(freed == 1);
    if (fails) { printf("C-SMOKE FAILED (%d)\n", fails); return 1; }
    printf("C-SMOKE OK\n");
    return 0;
}
