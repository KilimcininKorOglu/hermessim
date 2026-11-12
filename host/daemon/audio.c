// hermessimd audio pump (M5). Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
//
// While a call is up, two directions run on the sound card's own clock (no sleeps):
//   caller -> client : capture PCM (VOC_REC_DL, 16 kHz mono, 10 ms reads) -> Opus encode (10 or 20 ms) -> audio client
//   client -> caller : audio client -> playout (playout.c) -> Opus decode (FEC/PLC) -> injection PCM
// The "audio client" is the host app, which carries these packets over Bluetooth or the internet; the phone runs the
// mirror-image Opus codec. One datagram on the socket = one packet of protocol/README.md "Call audio":
//     [u16 tick little-endian][opus payload]        (tick: 10 ms steps of the sender's clock)
// Opus is sample-rate agnostic at its boundary: we encode 16 kHz (the call-record rate) and decode to
// 48 kHz mono directly (the injection PCM rate), so no handwritten resampler — just duplicate to stereo.
//
// PCM I/O uses the phone's own libtinyalsa via dlopen (see ta_load for why).
// Threads: one always-on I/O thread owns the client socket (accept + read into the jitter buffer); the
// capture and playback threads exist only during a call and block on the PCM, which paces them.
#include "audio.h"
#include "playout.h"

#include <android/log.h>
#include <errno.h>
#include <poll.h>
#include <opus.h>
#include <pthread.h>
#include <sched.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sound/asound.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define TAG "hermessimd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---- PCM I/O through the phone's own libtinyalsa (dlopen) ----
// Stock tinyalsa 2.0.0, compiled in, misbehaved on SM8250 (15 Oct 2026): in-call capture faulted with
// EFAULT or free-ran at 30x real time, and Incall_Music playback consumed ~20 kHz instead of 48 kHz with
// correct negotiated params. The system's own libtinyalsa.so (what stock tinycap/tinyplay use) runs
// both in real time with 10 ms periods and 0 errors. Its pcm_config layout matches; I/O is byte-based.
struct pcm;
struct pcm_config {
    unsigned channels, rate, period_size, period_count;
    int format;
    unsigned start_threshold, stop_threshold, silence_threshold, silence_size;
    int avail_min;
};
#define PCM_OUT 0x00000000u
#define PCM_IN 0x10000000u
#define PCM_FORMAT_S16_LE 0
static struct {
    struct pcm *(*open)(unsigned, unsigned, unsigned, struct pcm_config *);
    int (*is_ready)(struct pcm *);
    const char *(*get_error)(struct pcm *);
    int (*read)(struct pcm *, void *, unsigned);
    int (*write)(struct pcm *, const void *, unsigned);
    int (*close)(struct pcm *);
    int (*prepare)(struct pcm *);
    int (*stop)(struct pcm *);       // optional: wakes a reader/writer blocked in the kernel (SNDRV_PCM_IOCTL_DROP)
} ta;

static int opus_rate_ok(int r) { return r == 8000 || r == 12000 || r == 16000 || r == 24000 || r == 48000; }

// What a PCM accepts right now (kernel HW_REFINE on the device node): profiles can be wrong or firmware-specific
// (Redmi Note 8 Pro: the profile said 16 kHz inject, but during a call the DSP pins Playback_2 to 48 kHz and every
// other rate fails with EINVAL; 19 Oct). Keeps the wanted rate/channels when accepted, else the nearest Opus rate
// and a channel count in range. Returns 0 when it could ask (device free), -1 otherwise (then: as wanted).
static int pcm_negotiate(int card, int device, int playback, int want_rate, int want_ch, int *rate, int *ch) {
    *rate = want_rate; *ch = want_ch;
    char path[64];
    snprintf(path, sizeof(path), "/dev/snd/pcmC%dD%d%c", card, device, playback ? 'p' : 'c');
    int fd = open(path, O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;
    struct snd_pcm_hw_params p;
    memset(&p, 0, sizeof(p));
    for (int i = 0; i <= SNDRV_PCM_HW_PARAM_LAST_MASK - SNDRV_PCM_HW_PARAM_FIRST_MASK; i++) memset(p.masks[i].bits, 0xff, sizeof(p.masks[i].bits));
    for (int i = 0; i <= SNDRV_PCM_HW_PARAM_LAST_INTERVAL - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL; i++) { p.intervals[i].min = 0; p.intervals[i].max = ~0u; }
    p.rmask = ~0u;
    int ok = ioctl(fd, SNDRV_PCM_IOCTL_HW_REFINE, &p) == 0;
    close(fd);
    if (!ok) return -1;
    const struct snd_interval *r = &p.intervals[SNDRV_PCM_HW_PARAM_RATE - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL];
    const struct snd_interval *c = &p.intervals[SNDRV_PCM_HW_PARAM_CHANNELS - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL];
    if ((unsigned)want_rate < r->min || (unsigned)want_rate > r->max) {
        static const int opus[] = { 48000, 16000, 24000, 12000, 8000 };
        int best = 0;
        for (size_t i = 0; i < sizeof(opus) / sizeof(opus[0]); i++) {
            if ((unsigned)opus[i] < r->min || (unsigned)opus[i] > r->max) continue;
            if (!best || abs(opus[i] - want_rate) < abs(best - want_rate)) best = opus[i];
        }
        if (best) {
            LOGW("pcm %d %s: profile rate %d not accepted now (%u..%u Hz): using %d", device, playback ? "out" : "in",
                 want_rate, r->min, r->max, best);
            *rate = best;
        }
    }
    if ((unsigned)want_ch < c->min) *ch = (int)c->min;
    else if ((unsigned)want_ch > c->max) *ch = (int)c->max;
    if (*ch != want_ch) LOGW("pcm %d %s: %d channel(s) not accepted: using %d", device, playback ? "out" : "in", want_ch, *ch);
    if (*ch > 2) *ch = 2;
    return 0;
}

// First call loads the library; capture and playback can reach this at the same time on the
// first CALL_START. The mutex serializes the init and publishes every symbol before any other
// thread can see ta.open, so nobody can call a half-filled table.
static pthread_mutex_t ta_lock = PTHREAD_MUTEX_INITIALIZER;

static int ta_load(void) {
    pthread_mutex_lock(&ta_lock);
    if (ta.open) { pthread_mutex_unlock(&ta_lock); return 0; }
    // The system's own tinyalsa (what stock tinycap/tinyplay use); vendors keep it in different partitions. Our own
    // word size: lib64 for the 64-bit build, lib for the 32-bit one (32-bit Android has no lib64).
#ifdef __LP64__
#define LIBDIR "lib64"
#else
#define LIBDIR "lib"
#endif
    static const char *paths[] = { "/system/" LIBDIR "/libtinyalsa.so", "/vendor/" LIBDIR "/libtinyalsa.so",
                                   "/system_ext/" LIBDIR "/libtinyalsa.so", "/odm/" LIBDIR "/libtinyalsa.so" };
    void *h = NULL;
    for (size_t i = 0; i < sizeof(paths) / sizeof(paths[0]) && !h; i++) h = dlopen(paths[i], RTLD_NOW);
    if (!h) {
        __android_log_print(ANDROID_LOG_ERROR, "hermessimd", "dlopen libtinyalsa: %s", dlerror());
        pthread_mutex_unlock(&ta_lock);
        return -1;
    }
    ta.open = dlsym(h, "pcm_open"); ta.is_ready = dlsym(h, "pcm_is_ready"); ta.get_error = dlsym(h, "pcm_get_error");
    ta.read = dlsym(h, "pcm_read"); ta.write = dlsym(h, "pcm_write"); ta.close = dlsym(h, "pcm_close");
    ta.prepare = dlsym(h, "pcm_prepare");
    ta.stop = dlsym(h, "pcm_stop");
    if (!ta.open || !ta.is_ready || !ta.get_error || !ta.read || !ta.write || !ta.close || !ta.prepare) {
        __android_log_print(ANDROID_LOG_ERROR, "hermessimd", "libtinyalsa: missing symbols");
        ta.open = NULL;
        pthread_mutex_unlock(&ta_lock);
        return -1;
    }
    pthread_mutex_unlock(&ta_lock);
    return 0;
}

#define SOCK_NAME "hermessimd-audio"
#define CAP_RATE 16000
#define CAP_FRAME 160          // 10 ms at 16 kHz (one Opus frame)
#define CAP_PERIOD CAP_FRAME   // 10 ms periods, one per read (one Opus frame)
#define CAP_PERIODS 4
#define CAP_READ CAP_PERIOD
#define CAP_FRAMES_PER_READ (CAP_READ / CAP_FRAME)
#define CAP_MAX_ERRORS 600      // consecutive read errors (~60 s at 100 ms backoff) before giving up for this call
#define CAP_SLOW_AFTER 5        // after this many consecutive errors, retry only every 100 ms (protects the DSP)
#define DEC_RATE 48000
#define DEC_FRAME 480          // 10 ms at 48 kHz
#define MAX_PACKET PLAYOUT_MAX_PACKET
#define MAX_OPUS 250           // an encoded packet's ceiling (protocol/README.md "Call audio")
#define HDR 2                  // [u16 tick]
#define BITRATE 24000
#define LOSS_DEFAULT 10        // expected loss until the phone's AudioReport says otherwise (LOSS)

// Tunable live (TUNE command) so latency trade-offs can be compared within one call (17 Oct 2026):
static volatile int g_play_period = 480; // inject PCM period in frames at 48 kHz (480 = 10 ms, 240 = 5 ms); 2 periods
// Set by the host app (FRAME, LOSS) when the call's audio changes path or the phone reports its loss.
static volatile int g_frame_ticks = 1;   // capture packets of 1 (10 ms, Bluetooth) or 2 ticks (20 ms, internet)
static volatile int g_loss = LOSS_DEFAULT;

static audio_cfg_t g_cfg;
static int g_allowed_uid = -1;

// Audio client fd, written by the I/O thread, read+written by the capture thread.
static int g_client = -1;
static pthread_mutex_t g_client_lock = PTHREAD_MUTEX_INITIALIZER;

// Playout (client -> caller), guarded by g_jb_lock. Its prime and keep are TUNE's.
static pthread_mutex_t g_jb_lock = PTHREAD_MUTEX_INITIALIZER;
static playout_t g_play;

// Call lifecycle.
static volatile int g_call_running = 0;
static pthread_t g_io_t, g_cap_t, g_play_t;
static int g_cap_started, g_play_started;     // joined only if created
static int g_io_started = 0;

// Stats (reset each 5 s log line).
static unsigned g_st_sent, g_st_recv, g_st_plc, g_st_fec, g_st_late, g_st_catchup, g_st_caperr;
// Packets the audio client's socket had no room for (EAGAIN): its reader fell behind, they were dropped (capture thread).
static unsigned g_st_txdrop;
static int g_st_peak;
// Totals since the call began, for the call test (AUDIO?): did the caller's voice reach us, did we play into the call.
static unsigned g_tot_cap, g_tot_voiced, g_tot_sent, g_tot_played;
// Call quality, per call (the app's end-of-call check): caller audio the client's socket had no room for, or
// captured with no client connected; client audio received, concealed (PLC), and pulls that found nothing to
// play, counted only while a client is connected (a hang-up's tail isn't a loss).
static unsigned g_tot_txdrop, g_tot_noclient, g_tot_recv, g_tot_plc, g_tot_starved, g_tot_live;
// The capture against the clock: the time its reads took while the stream worked (ms, read to read with no error
// between) and its read errors. A voice path that hands over less than real time loses caller audio before anything
// here can drop it, so no other total shows it (a Galaxy A13's phone got 85 of 100 frames a second and the end-of-call
// check saw nothing wrong, 10 Nov 2026).
static unsigned g_tot_capms, g_tot_caperr;
static volatile int g_client_up;
static int g_tot_peak;
#define VOICED_PEAK 1000          // |sample| above this in a read = someone is talking (silence/comfort noise stays far below)
static unsigned g_st_played, g_st_depth_max;
// Measured time audio spends in our buffers (sums over the 5 s window): jitter-buffer depth at each pull, frames
// queued in the inject PCM after each write, frames waiting in the capture PCM before each read.
static double g_st_jb_sum;
static unsigned g_st_jb_n, g_st_reopen;
static volatile int g_stall_ms;          // debug: one playback stall (STALL command)   // playback frames written; max jitter depth   // max |sample| captured in the window: 0 = the caller path is silent

// The capture, playback and I/O threads all update these; the 5 s line snapshots and clears them from
// the capture thread. One lock (a few instructions per frame) keeps the diagnostics consistent and the
// accesses defined. Jitter-buffer code takes it while already holding g_jb_lock: that order only.
static pthread_mutex_t g_stats_lock = PTHREAD_MUTEX_INITIALIZER;
static void st_inc(unsigned *v) { pthread_mutex_lock(&g_stats_lock); (*v)++; pthread_mutex_unlock(&g_stats_lock); }
static void st_add(unsigned *v, unsigned n) { pthread_mutex_lock(&g_stats_lock); *v += n; pthread_mutex_unlock(&g_stats_lock); }
static void st_peak(int *v, int peak) { pthread_mutex_lock(&g_stats_lock); if (peak > *v) *v = peak; pthread_mutex_unlock(&g_stats_lock); }
static void st_umax(unsigned *v, unsigned value) { pthread_mutex_lock(&g_stats_lock); if (value > *v) *v = value; pthread_mutex_unlock(&g_stats_lock); }
static void st_jb_depth(int d) {
    pthread_mutex_lock(&g_stats_lock);
    g_st_jb_sum += d < 0 ? 0 : d;
    g_st_jb_n++;
    pthread_mutex_unlock(&g_stats_lock);
}

static void set_realtime(const char *who) {
    struct sched_param sp = { .sched_priority = 2 };
    if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp) != 0) LOGW("%s: SCHED_FIFO refused (ok)", who);
}

static int log_throttle(struct timespec *last, int secs) {
    struct timespec now; clock_gettime(CLOCK_MONOTONIC, &now);
    if (now.tv_sec - last->tv_sec < secs) return 0;
    *last = now; return 1;
}

// ------------------------------------------------------------------ client socket

static void client_set(int fd) {
    pthread_mutex_lock(&g_client_lock);
    int old = g_client;
    g_client = fd;
    g_client_up = fd >= 0;
    pthread_mutex_unlock(&g_client_lock);
    if (old >= 0 && old != fd) close(old);
}

static void client_send(const uint8_t *buf, int len) {
    pthread_mutex_lock(&g_client_lock);
    int fd = g_client;
    if (fd < 0) st_inc(&g_tot_noclient);
    else if (send(fd, buf, len, MSG_DONTWAIT | MSG_NOSIGNAL) < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) { st_inc(&g_st_txdrop); st_inc(&g_tot_txdrop); }
        else LOGW("audio send: %s", strerror(errno));
    }
    pthread_mutex_unlock(&g_client_lock);
}

// ------------------------------------------------------------------ playout

static void jb_reset(void) {
    pthread_mutex_lock(&g_jb_lock);
    playout_reset(&g_play);
    pthread_mutex_unlock(&g_jb_lock);
}

// One packet from the client (or the loopback): its length comes from the Opus TOC.
static void jb_put(uint16_t tick, const uint8_t *data, int len) {
    int samples = len > 0 ? opus_packet_get_nb_samples(data, len, DEC_RATE) : -1;
    if (samples < DEC_FRAME || samples % DEC_FRAME != 0) return;   // not a whole number of 10 ms steps
    pthread_mutex_lock(&g_jb_lock);
    int r = playout_put(&g_play, tick, samples / DEC_FRAME, data, len);
    pthread_mutex_unlock(&g_jb_lock);
    if (r == PLAYOUT_PUT_LATE || r == PLAYOUT_PUT_RESYNC) st_inc(&g_st_late);
    if (r == PLAYOUT_PUT_RESYNC) LOGW("audio playout follows the sender from tick %u", tick);
    if (r == PLAYOUT_PUT_OK || r == PLAYOUT_PUT_RESYNC) { st_inc(&g_st_recv); st_inc(&g_tot_recv); }
}

// The playout saw this client's first packet (the call quality counters start there).
static int jb_started(void) {
    pthread_mutex_lock(&g_jb_lock);
    int started = g_play.started;
    pthread_mutex_unlock(&g_jb_lock);
    return started;
}

// One 10 ms tick from the playout (protocol/README.md "Playout"); FEC, concealment and trimming are counted here.
static playout_out_t jb_take(uint8_t *buf) {
    pthread_mutex_lock(&g_jb_lock);
    unsigned dropped = g_play.dropped;
    playout_out_t out = playout_take(&g_play, buf);
    int depth = playout_depth(&g_play);
    dropped = g_play.dropped - dropped;
    pthread_mutex_unlock(&g_jb_lock);
    if (out.kind == PLAYOUT_SILENCE) return out;
    st_jb_depth(depth);
    if (dropped) st_add(&g_st_catchup, dropped);
    if (out.kind == PLAYOUT_FEC) st_inc(&g_st_fec);
    if (out.kind == PLAYOUT_PLC) { st_inc(&g_st_plc); st_inc(&g_tot_plc); }
    return out;
}

static void *io_main(void *arg) {
    (void)arg;
    struct sockaddr_un a;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    memcpy(a.sun_path + 1, SOCK_NAME, strlen(SOCK_NAME));
    socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(SOCK_NAME));

    int ls = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (ls < 0 || bind(ls, (struct sockaddr *)&a, alen) < 0 || listen(ls, 1) < 0) {
        LOGE("audio socket @%s: %s", SOCK_NAME, strerror(errno));
        return NULL;
    }
    LOGI("audio socket @%s listening", SOCK_NAME);

    // One audio client at a time, but a NEW client replaces the current one: a client that went stale (a remote
    // session whose call ended, an L2CAP bridge whose link died) must never keep the next call's audio out.
    uint8_t buf[HDR + MAX_PACKET];
    int fd = -1;
    for (;;) {
        struct pollfd pf[2] = { { .fd = ls, .events = POLLIN }, { .fd = fd, .events = POLLIN } };
        int n = poll(pf, fd >= 0 ? 2 : 1, -1);
        if (n < 0) { if (errno == EINTR) continue; break; }
        if (pf[0].revents & POLLIN) {
            int nfd = accept4(ls, NULL, NULL, SOCK_CLOEXEC);
            if (nfd >= 0) {
                struct ucred cr = { 0 };
                socklen_t cl = sizeof(cr);
                if (getsockopt(nfd, SOL_SOCKET, SO_PEERCRED, &cr, &cl) < 0 ||
                    (cr.uid != 0 && (int)cr.uid != g_allowed_uid)) {
                    LOGW("audio client uid=%d REJECTED", cl ? (int)cr.uid : -1);
                    close(nfd);
                } else {
                    LOGI("audio client connected (uid=%d)%s", (int)cr.uid, fd >= 0 ? ", replacing the previous one" : "");
                    client_set(nfd);                     // closes the previous client's fd
                    fd = nfd;
                    jb_reset();
                    continue;
                }
            }
        }
        if (fd >= 0 && (pf[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t got = recv(fd, buf, sizeof(buf), 0);
            if (got <= 0) {
                LOGI("audio client gone");
                pthread_mutex_lock(&g_client_lock);
                int mine = g_client == fd;
                if (mine) { g_client = -1; g_client_up = 0; }
                pthread_mutex_unlock(&g_client_lock);
                if (mine) close(fd);
                fd = -1;
            } else if (got >= HDR) {
                uint16_t seq = (uint16_t)(buf[0] | (buf[1] << 8));
                jb_put(seq, buf + HDR, (int)got - HDR);
            }
        }
    }
    close(ls);
    return NULL;
}

// ------------------------------------------------------------------ capture (caller -> client)

static struct pcm *open_pcm_n(int device, unsigned flags, unsigned rate, unsigned channels, unsigned frame, unsigned periods);
static struct pcm *open_pcm(int device, unsigned flags, unsigned rate, unsigned channels, unsigned frame) {
    return open_pcm_n(device, flags, rate, channels, frame, flags == PCM_IN ? CAP_PERIODS : 2);
}

static struct pcm *open_pcm_n(int device, unsigned flags, unsigned rate, unsigned channels, unsigned frame, unsigned periods) {
    struct pcm_config c = {
        .channels = channels, .rate = rate, .period_size = frame, .period_count = periods,
        .format = PCM_FORMAT_S16_LE,
        // Capture: library defaults. Playback: start after one period (low latency), and never let ALSA stop the
        // stream on its own: the DSP pre-fetches, so the ALSA ring can look empty while audio is still playing.
        // A stop_threshold of one buffer turned that into an underrun on every write: 2/3-speed, robotic uplink
        // for whole calls (17 Oct 2026). INT_MAX is what Qualcomm's own audio HAL uses for its outputs.
        .start_threshold = (flags == PCM_IN) ? 0 : frame,
        .stop_threshold = (flags == PCM_IN) ? 0 : 0x7fffffffu, .silence_threshold = 0,
    };
    if (ta_load() < 0) return NULL;
    struct pcm *pcm = ta.open((unsigned)g_cfg.card, (unsigned)device, flags, &c);
    if (!pcm || !ta.is_ready(pcm)) {
        LOGE("pcm_open card %d dev %d (%s): %s", g_cfg.card, device, flags == PCM_IN ? "in" : "out",
             pcm ? ta.get_error(pcm) : "null");
        if (pcm) ta.close(pcm);
        return NULL;
    }
    return pcm;
}

// The PCMs the pump threads hold right now, so CALL_END can wake a thread blocked in pcm_read/pcm_write (once the
// voice path stops, a read only returns on ALSA's ~10 s timeout, and the daemon's loop would wait in the join).
enum { LIVE_CAP, LIVE_PLAY };
static pthread_mutex_t g_live_lock = PTHREAD_MUTEX_INITIALIZER;
static struct pcm *g_live[2];

static struct pcm *pcm_hold(int slot, struct pcm *pcm) {
    pthread_mutex_lock(&g_live_lock); g_live[slot] = pcm; pthread_mutex_unlock(&g_live_lock);
    return pcm;
}

static void pcm_release(int slot, struct pcm *pcm) {
    if (!pcm) return;
    pthread_mutex_lock(&g_live_lock); if (g_live[slot] == pcm) g_live[slot] = NULL; pthread_mutex_unlock(&g_live_lock);
    ta.close(pcm);
}

static void pcm_wake_all(void) {
    pthread_mutex_lock(&g_live_lock);
    for (int i = 0; i < 2; i++) if (g_live[i] && ta.stop) ta.stop(g_live[i]);
    pthread_mutex_unlock(&g_live_lock);
}

// The capture's own clock for g_tot_capms: the time between good reads that follow each other.
typedef struct {
    struct timespec prev;          // the last good read, {0} after an error
    unsigned long long ns;
} cap_clock_t;

static void cap_clock_read(cap_clock_t *c) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (c->prev.tv_sec || c->prev.tv_nsec) {
        c->ns += (unsigned long long)(now.tv_sec - c->prev.tv_sec) * 1000000000ull
               + (unsigned long long)now.tv_nsec - (unsigned long long)c->prev.tv_nsec;
        st_umax(&g_tot_capms, (unsigned)(c->ns / 1000000ull));
    }
    c->prev = now;
}

// A failed read: counted, and the stream isn't running, so the gap up to the next good read isn't a slow capture.
static void cap_clock_error(cap_clock_t *c) {
    st_inc(&g_tot_caperr);
    c->prev.tv_sec = 0; c->prev.tv_nsec = 0;
}

// Encodes the gathered `ticks` (10 or 20 ms) and hands the packet to the client; returns the next packet's tick.
static uint16_t encode_send(OpusEncoder *enc, const int16_t *pcm, int samples, int ticks, uint16_t tick) {
    uint8_t pkt[HDR + MAX_OPUS];
    int len = opus_encode(enc, pcm, samples, pkt + HDR, MAX_OPUS);
    if (len < 0) LOGW("opus encode: %d", len);
    if (len <= 2) return (uint16_t)(tick + ticks);   // an error or DTX: nothing to send, but the tick is time
    pkt[0] = (uint8_t)(tick & 0xff);
    pkt[1] = (uint8_t)(tick >> 8);
    client_send(pkt, HDR + len);
    if (g_cfg.loopback) jb_put(tick, pkt + HDR, len);
    st_inc(&g_st_sent);
    st_inc(&g_tot_sent);
    return (uint16_t)(tick + ticks);
}

// The expected loss the phone's last report asked for (LOSS), set on the encoder when it changed.
static void follow_loss(OpusEncoder *enc, int *loss) {
    int want = g_loss;
    if (want == *loss) return;
    opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(want));
    *loss = want;
}

// The 5 s line: what flowed each way and what the buffers held, from the capture thread.
static void log_window(unsigned frames, double s) {
    pthread_mutex_lock(&g_stats_lock);
    unsigned sent = g_st_sent, txdrop = g_st_txdrop, recv = g_st_recv, fec = g_st_fec, plc = g_st_plc;
    unsigned late = g_st_late, catchup = g_st_catchup, caperr = g_st_caperr;
    int peak = g_st_peak;
    unsigned played = g_st_played, depth_max = g_st_depth_max, reopen = g_st_reopen, jb_n = g_st_jb_n;
    double jb_sum = g_st_jb_sum;
    g_st_jb_sum = 0; g_st_jb_n = 0; g_st_reopen = 0;
    g_st_sent = g_st_txdrop = g_st_recv = g_st_fec = g_st_plc = g_st_late = g_st_catchup = g_st_caperr = 0;
    g_st_peak = 0; g_st_played = 0; g_st_depth_max = 0;
    pthread_mutex_unlock(&g_stats_lock);
    pthread_mutex_lock(&g_jb_lock);
    int prime = g_play.prime, keep = g_play.keep;
    pthread_mutex_unlock(&g_jb_lock);
    LOGI("audio 5s: sent=%u txdrop=%u recv=%u fec=%u plc=%u late=%u catchup=%u caperr=%u peak=%d played=%u depthmax=%u (%.1f fps) "
         "| held: jb=%.1fms reopen=%u (tune prime=%d keep=%d period=%d frame=%d loss=%d)",
         sent, txdrop, recv, fec, plc, late, catchup, caperr, peak,
         played, depth_max, frames / s,
         jb_n ? jb_sum / jb_n * 10.0 : -1.0, reopen,
         prime, keep, g_play_period, g_frame_ticks * 10, g_loss);
}

static void *capture_main(void *arg) {
    (void)arg;
    set_realtime("capture");
    int err = 0;
    // Rate and channels from the profile (Opus: 8/12/16/24/48 kHz); a stereo capture is mixed down to mono.
    int crate = opus_rate_ok(g_cfg.capture_rate) ? g_cfg.capture_rate : CAP_RATE;
    int cch = g_cfg.capture_channels == 2 ? 2 : 1;
    pcm_negotiate(g_cfg.card, g_cfg.capture_pcm, 0, crate, cch, &crate, &cch);
    if (!opus_rate_ok(crate)) crate = CAP_RATE;      // pcmbuf/cframe below assume an Opus rate
    if (cch < 1 || cch > 2) cch = 1;                 // pcmbuf holds at most one stereo 10 ms frame
    const int cframe = crate / 100;                  // 10 ms
    OpusEncoder *enc = opus_encoder_create(crate, 1, OPUS_APPLICATION_VOIP, &err);
    if (!enc) { LOGE("opus encoder: %d", err); return NULL; }
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(BITRATE));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(LOSS_DEFAULT));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));

    struct pcm *pcm = pcm_hold(LIVE_CAP, open_pcm(g_cfg.capture_pcm, PCM_IN, (unsigned)crate, (unsigned)cch, (unsigned)cframe));
    if (!pcm) { opus_encoder_destroy(enc); return NULL; }

    int16_t pcmbuf[480 * 2];
    int16_t gathered[480 * 2];                       // up to two 10 ms reads for one 20 ms packet
    int have = 0, loss = LOSS_DEFAULT;
    uint16_t tick = 0;
    struct timespec t0;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    unsigned frames = 0;

    struct timespec err_log = { 0 };
    cap_clock_t cap_clk = { 0 };
    struct timespec backoff = { .tv_nsec = 10 * 1000 * 1000 };    // 10 ms: don't spin when the backend isn't streaming
    // One 10 ms period per read, paced by the DSP. Errors are rare with the system tinyalsa; the cap below
    // still stops a failing stream from storming the audio DSP (a storm rebooted the phone once).
    unsigned consecutive_err = 0;
    while (g_call_running) {
        if (!pcm) {
            struct timespec slow = { .tv_nsec = 100 * 1000 * 1000 };
            pcm = pcm_hold(LIVE_CAP, open_pcm(g_cfg.capture_pcm, PCM_IN, (unsigned)crate, (unsigned)cch, (unsigned)cframe));
            if (!pcm) {
                if (++consecutive_err >= CAP_MAX_ERRORS) { LOGE("capture: cannot reopen, giving up for this call"); break; }
                nanosleep(&slow, NULL);
                continue;
            }
        }
        int got = ta.read(pcm, pcmbuf, (unsigned)(cframe * cch) * sizeof(int16_t)) == 0 ? cframe : -1;
        if (got != cframe) {                  // error: don't encode a partial/garbage frame
            if (log_throttle(&err_log, 2)) LOGW("capture read failed: %s", ta.get_error(pcm));
            if (++consecutive_err >= CAP_MAX_ERRORS) {
                LOGE("capture: %u consecutive errors, giving up for this call (protecting the audio DSP)", consecutive_err);
                break;
            }
            st_inc(&g_st_caperr);
            cap_clock_error(&cap_clk);
            if (consecutive_err >= CAP_SLOW_AFTER) {     // e.g. outgoing call still dialing: voice path not up yet
                struct timespec slow = { .tv_nsec = 100 * 1000 * 1000 };
                nanosleep(&slow, NULL);
            }
            // Isolated fault: that 10 ms (and what was gathered for its packet) is lost; the tick goes on so the
            // receiver conceals it.
            tick = (uint16_t)(tick + have + CAP_FRAMES_PER_READ);
            have = 0;
            if (consecutive_err == 1) continue;
            // Repeated faults: reopen the stream (fresh hw params).
            pcm_release(LIVE_CAP, pcm);
            nanosleep(&backoff, NULL);
            pcm = pcm_hold(LIVE_CAP, open_pcm(g_cfg.capture_pcm, PCM_IN, (unsigned)crate, (unsigned)cch, (unsigned)cframe));
            continue;                                   // NULL pcm is retried at the top of the loop
        }
        consecutive_err = 0;
        cap_clock_read(&cap_clk);
        if (cch == 2) for (int i = 0; i < cframe; i++) pcmbuf[i] = (int16_t)(((int)pcmbuf[2 * i] + pcmbuf[2 * i + 1]) / 2);
        int rpeak = 0;
        for (int i = 0; i < cframe; i++) { int a = pcmbuf[i] < 0 ? -pcmbuf[i] : pcmbuf[i]; if (a > rpeak) rpeak = a; }
        st_peak(&g_st_peak, rpeak);
        st_peak(&g_tot_peak, rpeak);
        st_add(&g_tot_cap, CAP_FRAMES_PER_READ);
        if (rpeak > VOICED_PEAK) st_add(&g_tot_voiced, CAP_FRAMES_PER_READ);
        memcpy(gathered + have * cframe, pcmbuf, (size_t)cframe * sizeof(int16_t));
        if (++have >= (g_frame_ticks == 2 ? 2 : 1)) {
            follow_loss(enc, &loss);
            tick = encode_send(enc, gathered, have * cframe, have, tick);
            have = 0;
        }
        frames += CAP_FRAMES_PER_READ;
        if (frames >= 500) {     // ~5 s
            struct timespec now; clock_gettime(CLOCK_MONOTONIC, &now);
            log_window(frames, (now.tv_sec - t0.tv_sec) + (now.tv_nsec - t0.tv_nsec) / 1e9);
            frames = 0; t0 = now;
        }
    }
    if (pcm) pcm_release(LIVE_CAP, pcm);
    opus_encoder_destroy(enc);
    return NULL;
}

// ------------------------------------------------------------------ playback (client -> caller)

// What the last decode gave: its later ticks wait here for their CARRY takes.
typedef struct {
    int16_t pcm[480 * PLAYOUT_MAX_TICKS];
    int dframe;                                     // samples per tick at the inject rate
} held_t;

// The PCM of one take into `mono` (one tick): a packet decoded whole, FEC over a gap (Opus conceals the part FEC does
// not cover), concealment, or silence while the playout waits. Whatever a decode did not fill is silence.
static int decode_take(OpusDecoder *dec, playout_out_t take, const uint8_t *pkt, held_t *h, int16_t *mono) {
    const int dframe = h->dframe;
    int16_t *held = h->pcm;
    int want = dframe, got;
    switch (take.kind) {
    case PLAYOUT_PLAY:
        want = dframe * take.ticks;
        got = opus_decode(dec, pkt, take.len, held, dframe * PLAYOUT_MAX_TICKS, 0);
        break;
    case PLAYOUT_FEC:
        want = dframe * take.ticks;
        got = opus_decode(dec, pkt, take.len, held, want, 1);
        break;
    case PLAYOUT_PLC:
        got = opus_decode(dec, NULL, 0, held, dframe, 0);
        break;
    case PLAYOUT_CARRY:
        memcpy(mono, held + take.ticks * dframe, (size_t)dframe * sizeof(int16_t));
        return dframe;
    default:
        memset(mono, 0, (size_t)dframe * sizeof(int16_t));
        return dframe;
    }
    if (got < 0) got = 0;
    if (got < want) memset(held + got, 0, (size_t)(want - got) * sizeof(int16_t));
    memcpy(mono, held, (size_t)dframe * sizeof(int16_t));
    return dframe;
}

static void *playback_main(void *arg) {
    (void)arg;
    set_realtime("playback");
    int err = 0;
    // Decode straight at the inject PCM's rate (Opus: 8/12/16/24/48 kHz), 10 ms per write at any rate.
    const int want = opus_rate_ok(g_cfg.inject_rate) ? g_cfg.inject_rate : DEC_RATE;
    if (want != g_cfg.inject_rate) LOGW("inject rate %d isn't an Opus rate: playing at %d", g_cfg.inject_rate, want);
    int drate = want, ich = g_cfg.inject_channels == 2 ? 2 : 1;
    pcm_negotiate(g_cfg.card, g_cfg.inject_pcm, 1, want, ich, &drate, &ich);
    if (!opus_rate_ok(drate)) drate = DEC_RATE;      // mono/out below assume an Opus rate
    if (ich < 1 || ich > 2) ich = 1;                 // out holds at most one stereo 10 ms frame
    int dframe = drate / 100;
    OpusDecoder *dec = opus_decoder_create(drate, 1, &err);
    if (!dec) { LOGE("opus decoder: %d", err); return NULL; }

    int base = g_play_period;                          // TUNE periods are given in 48 kHz frames
    int period = base * drate / 48000;
    struct pcm *pcm = pcm_hold(LIVE_PLAY, open_pcm(g_cfg.inject_pcm, PCM_OUT, (unsigned)drate, (unsigned)ich, (unsigned)period));
    for (int tries = 1; !pcm && g_call_running; tries++) {     // the voice path may not be up yet: retry, don't give up
        // Quick at first (audio must start as the call connects), then easing off: a voice path that never opens
        // must not be hit 5 times a second for a whole call (an open/close storm on the audio DSP rebooted a phone).
        struct timespec wait = { .tv_sec = tries > 85 ? 1 : 0,
                                 .tv_nsec = tries > 85 ? 0 : tries > 25 ? 500 * 1000 * 1000 : 200 * 1000 * 1000 };
        nanosleep(&wait, NULL);
        // The voice path's constraints appear once the modem side runs: ask again, follow a changed rate.
        int r2 = drate, c2 = ich;
        if (pcm_negotiate(g_cfg.card, g_cfg.inject_pcm, 1, want, ich, &r2, &c2) == 0 &&
            (r2 != drate || c2 != ich) && opus_rate_ok(r2) && c2 >= 1 && c2 <= 2) {
            OpusDecoder *d2 = opus_decoder_create(r2, 1, &err);
            if (d2) { opus_decoder_destroy(dec); dec = d2; drate = r2; ich = c2; dframe = drate / 100; period = base * drate / 48000; }
        }
        pcm = pcm_hold(LIVE_PLAY, open_pcm_n(g_cfg.inject_pcm, PCM_OUT, (unsigned)drate, (unsigned)ich, (unsigned)period, 2));
        if (pcm) LOGI("inject PCM opened after %d tries", tries + 1);
    }
    if (!pcm) { opus_decoder_destroy(dec); return NULL; }
    jb_reset();                                         // packets queued while the voice path wasn't up are stale

    int16_t mono[480];
    static held_t held;                                 // a decoded packet's ticks, the later ones wait for their take
    int16_t out[480 * 2];
    uint8_t pkt[MAX_PACKET];
    unsigned periods = 2, win_frames = 0, slow_windows = 0;
    struct timespec win_t0;
    clock_gettime(CLOCK_MONOTONIC, &win_t0);
    struct timespec werr_log = { 0 };
    struct timespec wbackoff = { .tv_nsec = 10 * 1000 * 1000 };

    while (g_call_running) {
        if (base != g_play_period) {                    // TUNE changed the period: reopen with the new size
            pcm_release(LIVE_PLAY, pcm);
            base = g_play_period;
            period = base * drate / 48000;
            pcm = pcm_hold(LIVE_PLAY, open_pcm_n(g_cfg.inject_pcm, PCM_OUT, (unsigned)drate, (unsigned)ich, (unsigned)period, periods));
            LOGI("inject PCM reopened with %d-frame periods: %s", period, pcm ? "ok" : "FAILED");
            if (!pcm) {                                 // fall back to the proven 10 ms
                g_play_period = base = 480; period = dframe;
                pcm = pcm_hold(LIVE_PLAY, open_pcm(g_cfg.inject_pcm, PCM_OUT, (unsigned)drate, (unsigned)ich, (unsigned)dframe));
                if (!pcm) break;
            }
        }
        playout_out_t take = jb_take(pkt);
        if (g_client_up && jb_started()) { st_inc(&g_tot_live); if (take.kind == PLAYOUT_SILENCE) st_inc(&g_tot_starved); }
        held.dframe = dframe;
        int samples = decode_take(dec, take, pkt, &held, mono);

        st_inc(&g_st_played);
        st_inc(&g_tot_played);
        { pthread_mutex_lock(&g_jb_lock); int d = playout_depth(&g_play);
          if (g_play.playing) st_umax(&g_st_depth_max, d < 0 ? 0 : (unsigned)d); pthread_mutex_unlock(&g_jb_lock); }
        if (ich == 2) {
            for (int i = 0; i < samples; i++) { out[2 * i] = mono[i]; out[2 * i + 1] = mono[i]; }
            if (ta.write(pcm, out, (unsigned)samples * 2 * sizeof(int16_t)) != 0) { if (log_throttle(&werr_log, 2)) LOGW("inject write: %s", ta.get_error(pcm)); ta.prepare(pcm); nanosleep(&wbackoff, NULL); }
        } else {
            if (ta.write(pcm, mono, (unsigned)samples * sizeof(int16_t)) != 0) { if (log_throttle(&werr_log, 2)) LOGW("inject write: %s", ta.get_error(pcm)); ta.prepare(pcm); nanosleep(&wbackoff, NULL); }
        }
        // Self-healing: the write loop is paced by the DSP, so a healthy stream runs at 100 frames/s. Below 90/s
        // for two seconds running means the stream is stuck in a bad state: reopen it, with one more period of
        // headroom each time it happens again in this call (2 -> 3 -> 4 periods).
        if (++win_frames >= 100) {
            struct timespec now; clock_gettime(CLOCK_MONOTONIC, &now);
            double secs = (now.tv_sec - win_t0.tv_sec) + (now.tv_nsec - win_t0.tv_nsec) / 1e9;
            slow_windows = (secs > 100 / 90.0) ? slow_windows + 1 : 0;
            if (slow_windows >= 2 && pcm) {
                if (periods < 4) periods++;
                LOGW("inject stream running at %.0f frames/s: reopening with %u periods", 100 / secs, periods);
                pcm_release(LIVE_PLAY, pcm);
                pcm = pcm_hold(LIVE_PLAY, open_pcm_n(g_cfg.inject_pcm, PCM_OUT, (unsigned)drate, (unsigned)ich,
                                 (unsigned)period, periods));
                st_inc(&g_st_reopen);
                slow_windows = 0;
                if (!pcm) break;
            }
            win_frames = 0;
            clock_gettime(CLOCK_MONOTONIC, &win_t0);
        }
        if (g_stall_ms > 0) {                            // debug (STALL): reproduce a scheduling hiccup
            struct timespec st = { .tv_sec = g_stall_ms / 1000, .tv_nsec = (g_stall_ms % 1000) * 1000000L };
            LOGW("debug stall %d ms", g_stall_ms);
            g_stall_ms = 0;
            nanosleep(&st, NULL);
        }
    }
    pcm_release(LIVE_PLAY, pcm);
    opus_decoder_destroy(dec);
    return NULL;
}

// ------------------------------------------------------------------ lifecycle

void audio_init(int allowed_uid) {
    g_allowed_uid = allowed_uid;
    playout_init(&g_play);
    if (pthread_create(&g_io_t, NULL, io_main, NULL) == 0) g_io_started = 1;
    else LOGE("audio io thread: %s", strerror(errno));
}

int audio_tune(int prime, int keep, int period) {
    if (prime < 1 || prime > 8 || keep < 0 || keep > 6 || (period != 240 && period != 480 && period != 960)) return -1;
    pthread_mutex_lock(&g_jb_lock);
    g_play.prime = prime; g_play.keep = keep;
    pthread_mutex_unlock(&g_jb_lock);
    g_play_period = period;
    LOGI("audio tune: prime=%d keep=%d period=%d", prime, keep, period);
    return 0;
}

void audio_stall(int ms) { if (ms > 0 && ms <= 500) g_stall_ms = ms; }

void audio_tune_get(char *buf, size_t n) {
    pthread_mutex_lock(&g_jb_lock);
    int prime = g_play.prime, keep = g_play.keep;
    pthread_mutex_unlock(&g_jb_lock);
    snprintf(buf, n, "prime=%d keep=%d period=%d", prime, keep, g_play_period);
}

int audio_frame(int ms) {
    if (ms != 10 && ms != 20) return -1;
    if (g_frame_ticks != ms / 10) LOGI("audio frame: %d ms", ms);
    g_frame_ticks = ms / 10;
    return 0;
}

int audio_loss(int percent) {
    if (percent < 0 || percent > 100) return -1;
    g_loss = percent;
    return 0;
}

void audio_totals(char *buf, size_t n) {
    pthread_mutex_lock(&g_stats_lock);
    unsigned cap = g_tot_cap, voiced = g_tot_voiced, sent = g_tot_sent, played = g_tot_played;
    unsigned txdrop = g_tot_txdrop, noclient = g_tot_noclient, recv = g_tot_recv, plc = g_tot_plc;
    unsigned starved = g_tot_starved, live = g_tot_live, capms = g_tot_capms, caperr = g_tot_caperr;
    int peak = g_tot_peak;
    pthread_mutex_unlock(&g_stats_lock);
    snprintf(buf, n, "cap=%u voiced=%u sent=%u played=%u peak=%d txdrop=%u noclient=%u recv=%u plc=%u starved=%u live=%u"
             " capms=%u caperr=%u",
             cap, voiced, sent, played, peak, txdrop, noclient, recv, plc, starved, live, capms, caperr);
}

void audio_call_begin(const audio_cfg_t *cfg) {
    if (g_call_running) audio_call_end();          // log the old call's totals before clearing them
    pthread_mutex_lock(&g_stats_lock);
    g_tot_cap = g_tot_voiced = g_tot_sent = g_tot_played = 0;
    g_tot_txdrop = g_tot_noclient = g_tot_recv = g_tot_plc = g_tot_starved = g_tot_live = 0;
    g_tot_capms = g_tot_caperr = 0;
    g_tot_peak = 0;
    pthread_mutex_unlock(&g_stats_lock);
    g_cfg = *cfg;
    g_loss = LOSS_DEFAULT;                     // a new call starts without the phone's report
    jb_reset();
    g_call_running = 1;
    g_cap_started = pthread_create(&g_cap_t, NULL, capture_main, NULL) == 0;
    if (!g_cap_started) LOGE("capture thread: %s", strerror(errno));
    g_play_started = pthread_create(&g_play_t, NULL, playback_main, NULL) == 0;
    if (!g_play_started) LOGE("playback thread: %s", strerror(errno));
    LOGI("audio call begin (loopback=%d)", g_cfg.loopback);
}

void audio_call_end(void) {
    if (!g_call_running) return;
    g_call_running = 0;
    pcm_wake_all();                         // a read/write blocked on a stopped voice path returns now
    if (g_cap_started) pthread_join(g_cap_t, NULL);
    if (g_play_started) pthread_join(g_play_t, NULL);
    g_cap_started = g_play_started = 0;
    char t[320];
    audio_totals(t, sizeof(t));            // what flowed each way, for every call (diagnosis without a client)
    LOGI("audio call end: %s", t);
}

void audio_shutdown(void) {
    audio_call_end();
}
