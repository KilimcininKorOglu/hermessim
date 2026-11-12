// hermessimd call audio playout. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
//
// The playout rule of protocol/README.md "Playout": packets go in by tick (10 ms steps), one tick comes out per take.
// No locks, no ALSA, no Opus: audio.c holds the lock, finds each packet's length from its Opus TOC and decodes what a
// take names. protocol/v2/vectors/playout.txt is its test (tests/playout_test.c).
#ifndef HERMESSIMD_PLAYOUT_H
#define HERMESSIMD_PLAYOUT_H

#include <stdint.h>

#define PLAYOUT_SLOTS 64           // ring size (power of two)
#define PLAYOUT_MAX_PACKET 400
#define PLAYOUT_MAX_TICKS 6        // a packet holds 10 to 60 ms
#define PLAYOUT_MAX_GAP 4          // FEC is looked for this many ticks ahead
#define PLAYOUT_WINDOW 200         // takes (2 s) per adaptive-latency window
#define PLAYOUT_MAX_DEPTH 8        // above this, one tick is dropped per take
#define PLAYOUT_RESYNC 50          // late packets in a row: the sender started again

typedef struct {
    int valid;
    uint16_t tick;
    int ticks;
    int len;
    uint8_t data[PLAYOUT_MAX_PACKET];
} playout_slot_t;

typedef struct {
    playout_slot_t slot[PLAYOUT_SLOTS];
    int started, playing;
    uint16_t next;                 // the next tick to play
    uint16_t end;                  // the tick after the end of the latest packet
    int carry;                     // decoded ticks still to play
    int decoded;                   // ticks of the last decode
    int late_run;
    int win_min, win_n, trim;
    int prime, keep;               // ticks before playing, and the depth the trimming keeps (TUNE)
    unsigned received, lost, late, dropped;
} playout_t;

enum { PLAYOUT_SILENCE, PLAYOUT_PLAY, PLAYOUT_CARRY, PLAYOUT_FEC, PLAYOUT_PLC };
enum { PLAYOUT_PUT_OK, PLAYOUT_PUT_LATE, PLAYOUT_PUT_RESYNC, PLAYOUT_PUT_BAD };

typedef struct {
    int kind;
    uint16_t tick;                 // PLAY and FEC: the packet's tick
    int ticks;                     // PLAY: the packet's ticks; FEC: the gap decoded; CARRY: which decoded tick to play
    int len;                       // PLAY and FEC: bytes copied into the take's buffer
} playout_out_t;

// Empties the player; the counters and the tuning stay.
void playout_reset(playout_t *p);
// Sets up a new player with prime 2 and keep 1.
void playout_init(playout_t *p);
// One packet of `ticks` ticks at `tick`; PLAYOUT_PUT_* says what became of it.
int playout_put(playout_t *p, uint16_t tick, int ticks, const uint8_t *data, int len);
// One tick out. PLAY and FEC copy the packet into `buf` (PLAYOUT_MAX_PACKET bytes).
playout_out_t playout_take(playout_t *p, uint8_t *buf);
// Ticks buffered ahead of playback, decoded ones included.
int playout_depth(const playout_t *p);

#endif
