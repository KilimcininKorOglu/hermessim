// hermessimd playout test. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
//
// Runs every scenario of protocol/v2/vectors/playout.txt through playout.c and compares each take and the counters.
// Usage: playout_test <path to playout.txt>; exit 0 when all match.
#include "../playout.h"

#include <stdio.h>
#include <string.h>

static void describe(playout_out_t out, char *text, size_t n) {
    switch (out.kind) {
    case PLAYOUT_PLAY: snprintf(text, n, "play %u", out.tick); break;
    case PLAYOUT_CARRY: snprintf(text, n, "carry"); break;
    case PLAYOUT_FEC: snprintf(text, n, "fec %u %d", out.tick, out.ticks); break;
    case PLAYOUT_PLC: snprintf(text, n, "plc"); break;
    default: snprintf(text, n, "silence"); break;
    }
}

static int step(playout_t *p, const char *line, const char *scenario, int number) {
    static uint8_t data[1] = { 0x78 };
    uint8_t buf[PLAYOUT_MAX_PACKET];
    unsigned tick, ticks, received, lost, late;
    char got[64];
    if (sscanf(line, "put %u %u", &tick, &ticks) == 2) {
        playout_put(p, (uint16_t)tick, (int)ticks, data, 1);
        return 0;
    }
    if (strncmp(line, "take ", 5) == 0) {
        describe(playout_take(p, buf), got, sizeof(got));
        if (strcmp(got, line + 5) == 0) return 0;
        printf("FAIL %s, line %d: want \"%s\", got \"%s\"\n", scenario, number, line + 5, got);
        return 1;
    }
    if (sscanf(line, "count %u %u %u", &received, &lost, &late) == 3) {
        if (received == p->received && lost == p->lost && late == p->late) return 0;
        printf("FAIL %s: want counts %u %u %u, got %u %u %u\n", scenario, received, lost, late, p->received, p->lost, p->late);
        return 1;
    }
    printf("FAIL line %d: cannot read \"%s\"\n", number, line);
    return 1;
}

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "usage: playout_test playout.txt\n"); return 2; }
    FILE *f = fopen(argv[1], "r");
    if (!f) { perror(argv[1]); return 2; }
    static playout_t p;
    char line[256], scenario[200] = "";
    int number = 0, failures = 0, scenarios = 0;
    while (fgets(line, sizeof(line), f)) {
        number++;
        line[strcspn(line, "\n")] = 0;
        if (line[0] == '#' || line[0] == 0) continue;
        if (strncmp(line, "scenario ", 9) == 0) {
            snprintf(scenario, sizeof(scenario), "%s", line + 9);
            playout_init(&p);
            scenarios++;
            continue;
        }
        failures += step(&p, line, scenario, number);
    }
    fclose(f);
    printf("%s: %d scenarios, %d failures\n", failures ? "FAIL" : "PASS", scenarios, failures);
    return failures ? 1 : 0;
}
