// hermessimd call audio playout. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
#include "playout.h"

#include <string.h>

#define MASK (PLAYOUT_SLOTS - 1)

// a - b on the 16-bit tick circle: "empty" must never look like "65535 queued".
static int diff(uint16_t a, uint16_t b) { return (int16_t)(uint16_t)(a - b); }

static playout_slot_t *at(playout_t *p, uint16_t tick) {
    playout_slot_t *s = &p->slot[tick & MASK];
    return s->valid && s->tick == tick ? s : NULL;
}

static void clear(playout_t *p, uint16_t tick) {
    for (int i = 0; i < PLAYOUT_SLOTS; i++) p->slot[i].valid = 0;
    p->next = p->end = tick;
    p->playing = 0;
    p->carry = 0;
}

void playout_reset(playout_t *p) {
    clear(p, 0);
    p->started = 0;
    p->decoded = 0;
    p->late_run = 0;
    p->win_min = 1000; p->win_n = 0; p->trim = 0;
}

void playout_init(playout_t *p) {
    memset(p, 0, sizeof(*p));
    p->prime = 2;
    p->keep = 1;
    playout_reset(p);
}

int playout_depth(const playout_t *p) { return diff(p->end, p->next) + p->carry; }

// A packet older than the next tick: dropped, unless 50 in a row say the sender started again.
static int late(playout_t *p, uint16_t tick) {
    p->late++;
    if (++p->late_run < PLAYOUT_RESYNC) return PLAYOUT_PUT_LATE;
    clear(p, tick);
    p->late_run = 0;
    return PLAYOUT_PUT_RESYNC;
}

static int bad(int ticks, int len) {
    return ticks < 1 || ticks > PLAYOUT_MAX_TICKS || len <= 0 || len > PLAYOUT_MAX_PACKET;
}

static void store(playout_t *p, uint16_t tick, int ticks, const uint8_t *data, int len) {
    playout_slot_t *s = &p->slot[tick & MASK];
    s->valid = 1; s->tick = tick; s->ticks = ticks; s->len = len;
    memcpy(s->data, data, (size_t)len);
    uint16_t end = (uint16_t)(tick + ticks);
    if (diff(end, p->end) > 0) p->end = end;
    p->received++;
    if (!p->playing && playout_depth(p) >= p->prime) p->playing = 1;
}

int playout_put(playout_t *p, uint16_t tick, int ticks, const uint8_t *data, int len) {
    if (bad(ticks, len)) return PLAYOUT_PUT_BAD;
    if (!p->started) { p->started = 1; p->next = p->end = tick; }
    int result = PLAYOUT_PUT_OK;
    if (diff(tick, p->next) < 0 && (result = late(p, tick)) == PLAYOUT_PUT_LATE) return result;
    p->late_run = 0;
    if (diff(tick, p->next) >= PLAYOUT_SLOTS - 1) clear(p, tick);   // far ahead: play from it
    store(p, tick, ticks, data, len);
    return result;
}

// Drops one tick of buffered audio (latency trimming).
static void drop(playout_t *p) {
    p->dropped++;
    if (p->carry > 0) { p->carry--; return; }
    playout_slot_t *s = at(p, p->next);
    if (s) { s->valid = 0; p->next = (uint16_t)(p->next + s->ticks); return; }
    p->next++;
}

static playout_out_t copy_out(int kind, const playout_slot_t *s, int ticks, uint8_t *buf) {
    playout_out_t out = { .kind = kind, .tick = s->tick, .ticks = ticks, .len = s->len };
    memcpy(buf, s->data, (size_t)s->len);
    return out;
}

// The next tick is missing: FEC from the first later packet within 4 ticks when the gap covers its length, else one
// tick of concealment.
static playout_out_t recover(playout_t *p, uint8_t *buf) {
    for (int gap = 1; gap <= PLAYOUT_MAX_GAP; gap++) {
        playout_slot_t *s = at(p, (uint16_t)(p->next + gap));
        if (!s) continue;
        if (s->ticks > gap) break;
        p->next = (uint16_t)(p->next + gap);
        p->carry = gap - 1;
        p->decoded = gap;
        p->lost += (unsigned)gap;
        return copy_out(PLAYOUT_FEC, s, gap, buf);
    }
    p->next++;
    p->lost++;
    p->decoded = 1;
    return (playout_out_t){ .kind = PLAYOUT_PLC };
}

static playout_out_t pull(playout_t *p, uint8_t *buf) {
    if (p->carry > 0) {
        p->carry--;
        return (playout_out_t){ .kind = PLAYOUT_CARRY, .ticks = p->decoded - p->carry - 1 };
    }
    if (!p->playing) return (playout_out_t){ .kind = PLAYOUT_SILENCE };
    if (diff(p->end, p->next) <= 0) {                 // ran dry: wait for the prime again
        p->playing = 0;
        return (playout_out_t){ .kind = PLAYOUT_SILENCE };
    }
    playout_slot_t *s = at(p, p->next);
    if (!s) return recover(p, buf);
    s->valid = 0;
    p->next = (uint16_t)(p->next + s->ticks);
    p->carry = s->ticks - 1;
    p->decoded = s->ticks;
    return copy_out(PLAYOUT_PLAY, s, s->ticks, buf);
}

// Adaptive latency: when even a window's smallest depth was above `keep`, that much buffering was never needed, so
// it goes one tick per take. Above the hard cap one tick goes at every take.
static void adapt(playout_t *p) {
    int depth = playout_depth(p);
    if (depth < p->win_min) p->win_min = depth;
    if (++p->win_n >= PLAYOUT_WINDOW) {
        if (p->win_min > p->keep) p->trim = p->win_min - p->keep;
        p->win_min = 1000; p->win_n = 0;
    }
    if (p->trim > 0 && playout_depth(p) > p->keep) { drop(p); p->trim--; }
    if (playout_depth(p) > PLAYOUT_MAX_DEPTH) drop(p);
}

playout_out_t playout_take(playout_t *p, uint8_t *buf) {
    playout_out_t out = pull(p, buf);
    if (out.kind != PLAYOUT_SILENCE) adapt(p);
    return out;
}
