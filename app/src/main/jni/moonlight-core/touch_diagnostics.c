#include "touch_diagnostics.h"
#include "moonlight-common-c/src/Input.h"
#include <stdatomic.h>
#include <stdbool.h>
#include <pthread.h>
#include <string.h>
#include <time.h>

#define SLOTS 512
static atomic_bool enabled;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static struct { uint64_t token, time; uint32_t id; uint8_t type; } pending[SLOTS];
static uint64_t sequence, stats[12], controlStart;
static bool unavailable;

static uint64_t nowUs(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000 + t.tv_nsec / 1000;
}

void TouchDiagEnable(int value) {
    pthread_mutex_lock(&lock);
    atomic_store(&enabled, value != 0);
    memset(pending, 0, sizeof(pending));
    memset(stats, 0, sizeof(stats));
    controlStart = 0;
    unavailable = false;
    pthread_mutex_unlock(&lock);
}

uint64_t TouchDiagBegin(uint8_t type, uint32_t id) {
    if (!atomic_load(&enabled)) return 0;
    pthread_mutex_lock(&lock);
    uint64_t token = 0;
    if (atomic_load(&enabled)) {
        stats[0]++;
        if (type == 3) stats[1]++; // LI_TOUCH_EVENT_MOVE
        for (int i = 0; i < SLOTS; i++) {
            if (pending[i].token) continue;
            token = ++sequence;
            pending[i].token = token;
            pending[i].time = nowUs();
            pending[i].id = id;
            pending[i].type = type;
            break;
        }
        if (!token) { stats[10]++; unavailable = true; }
    }
    pthread_mutex_unlock(&lock);
    return token;
}

void TouchDiagComplete(uint64_t token, int result) {
    if (!token || result == 0) return;
    pthread_mutex_lock(&lock);
    stats[9]++;
    for (int i = 0; i < SLOTS; i++) {
        if (pending[i].token == token) { pending[i].token = 0; break; }
    }
    pthread_mutex_unlock(&lock);
}

void TouchDiagSnapshot(uint64_t out[12]) {
    pthread_mutex_lock(&lock);
    memcpy(out, stats, sizeof(stats));
    uint64_t now = nowUs();
    for (int i = 0; i < SLOTS; i++) {
        if (!pending[i].token) continue;
        out[5]++;
        uint64_t age = now - pending[i].time;
        if (age > out[6]) out[6] = age;
    }
    if (controlStart && now - controlStart > out[7]) out[7] = now - controlStart;
    out[11] = unavailable;
    memset(stats, 0, sizeof(stats));
    pthread_mutex_unlock(&lock);
}

static uint32_t little32(const unsigned char* data) {
    return data[0] | (uint32_t)data[1] << 8 | (uint32_t)data[2] << 16 | (uint32_t)data[3] << 24;
}

int __real_sendInputPacketOnControlStream(unsigned char*, int, uint8_t, uint32_t, bool);

// Link-time observation only: original bytes, flags, channel and return value are unchanged.
// This is handoff to the control transport, NOT socket delivery or a host acknowledgement.
int __wrap_sendInputPacketOnControlStream(unsigned char* data, int length, uint8_t channel,
                                          uint32_t flags, bool more) {
    if (!atomic_load(&enabled)) {
        return __real_sendInputPacketOnControlStream(data, length, channel, flags, more);
    }
    if (length < (int)sizeof(SS_TOUCH_PACKET) || little32(data + 4) != SS_TOUCH_MAGIC) {
        // Current common-c assigns touch channel 0x05. Unknown/encrypted layouts must
        // not be reported as a confirmed input queue stall.
        if (channel == 0x05) {
            pthread_mutex_lock(&lock); unavailable = true; pthread_mutex_unlock(&lock);
        }
        return __real_sendInputPacketOnControlStream(data, length, channel, flags, more);
    }
    uint8_t type = data[8];
    uint32_t id = little32(data + 12);
    uint64_t start = nowUs();
    pthread_mutex_lock(&lock);
    uint64_t oldest = UINT64_MAX;
    int index = -1;
    for (int i = 0; i < SLOTS; i++) {
        if (pending[i].token && pending[i].type == type && pending[i].id == id && pending[i].token < oldest) {
            index = i;
            oldest = pending[i].token;
        }
    }
    if (index >= 0) {
        uint64_t wait = start - pending[index].time;
        if (wait > stats[4]) stats[4] = wait;
        pending[index].token = 0;
    }
    stats[2]++;
    if (type == 3) stats[3]++;
    controlStart = start;
    pthread_mutex_unlock(&lock);
    int result = __real_sendInputPacketOnControlStream(data, length, channel, flags, more);
    uint64_t duration = nowUs() - start;
    pthread_mutex_lock(&lock);
    if (duration > stats[7]) stats[7] = duration;
    controlStart = 0;
    if (result < 0) stats[8]++;
    pthread_mutex_unlock(&lock);
    return result;
}
