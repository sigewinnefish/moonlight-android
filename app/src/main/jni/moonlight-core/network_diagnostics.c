#include "network_diagnostics.h"
#include <pthread.h>
#include <stdatomic.h>
#include <string.h>
#include <time.h>
#include <math.h>

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static atomic_bool enabled;
static uint64_t counts[32], total[2], lastPacket[2], lastSnapshot, lastControl;
static uint32_t audioPrevious[7], controlPrevious[4];
static int haveAudio, haveControl, haveSequence;
static uint64_t highest, seen[1024];
static uint64_t fecKeys[32];
static int fecCursor;
static double controlLoss, controlFlight;

static uint64_t nowUs(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000 + t.tv_nsec / 1000;
}

int NetDiagEnabled(void) { return atomic_load(&enabled); }

void NetDiagEnable(int value) {
    pthread_mutex_lock(&lock);
    atomic_store(&enabled, value != 0);
    memset(counts, 0, sizeof(counts)); memset(total, 0, sizeof(total));
    memset(lastPacket, 0, sizeof(lastPacket)); memset(seen, 0xff, sizeof(seen));
    memset(fecKeys, 0xff, sizeof(fecKeys)); fecCursor = 0;
    haveAudio = haveControl = haveSequence = 0;
    lastControl = 0; lastSnapshot = nowUs();
    pthread_mutex_unlock(&lock);
}

void NetDiagRtp(int audio, int length, uint16_t sequence) {
    if (!NetDiagEnabled() || length <= 0) return;
    uint64_t now = nowUs();
    pthread_mutex_lock(&lock);
    int stream = audio ? 1 : 0;
    counts[stream] += length; counts[2 + stream]++; total[stream]++;
    if (lastPacket[stream] && now - lastPacket[stream] > counts[4 + stream]) counts[4 + stream] = now - lastPacket[stream];
    lastPacket[stream] = now;
    if (!audio) {
        int16_t delta = (int16_t)(sequence - (uint16_t)highest);
        if (!haveSequence) { highest = 65536 + sequence; haveSequence = 1; delta = 0; }
        else if (delta > 0) { highest += delta; if (delta > 1) counts[31] += delta - 1; }
        else if (delta < 0) counts[6]++;
        uint64_t extended = highest + (delta < 0 ? delta : 0);
        if (highest - extended < 1024) {
            if (seen[extended % 1024] == extended) counts[7]++;
            else seen[extended % 1024] = extended;
        }
    }
    pthread_mutex_unlock(&lock);
}

void NetDiagAudio(const uint32_t counters[7]) {
    if (!NetDiagEnabled()) return;
    pthread_mutex_lock(&lock);
    const int target[7] = {11, 12, 13, 14, 15, 16, 27};
    if (haveAudio) for (int i = 0; i < 7; i++) counts[target[i]] += (uint32_t)(counters[i] - audioPrevious[i]);
    memcpy(audioPrevious, counters, sizeof(audioPrevious)); haveAudio = 1;
    pthread_mutex_unlock(&lock);
}

void NetDiagFec(uint32_t frame, uint8_t block, uint16_t data, uint16_t parity, uint16_t receivedData, uint16_t receivedParity) {
    if (!NetDiagEnabled() || !data || receivedData > data || receivedParity > parity) return;
    pthread_mutex_lock(&lock);
    uint64_t key = ((uint64_t)frame << 8) | block;
    for (int i = 0; i < 32; i++) if (fecKeys[i] == key) { pthread_mutex_unlock(&lock); return; }
    fecKeys[fecCursor++ % 32] = key;
    if (fecCursor == 32) fecCursor = 0;
    unsigned missing = data - receivedData;
    counts[10] += missing;
    if ((unsigned)receivedData + receivedParity >= data) { counts[8] += missing; if (missing) counts[28]++; }
    else counts[9]++;
    pthread_mutex_unlock(&lock);
}

void NetDiagControl(uint32_t sentBytes, uint32_t receivedBytes, uint32_t sentPackets, uint32_t receivedPackets, double lossPercent, uint32_t inFlight) {
    if (!NetDiagEnabled()) return;
    const uint32_t current[4] = {sentBytes, receivedBytes, sentPackets, receivedPackets};
    pthread_mutex_lock(&lock);
    if (haveControl) for (int i = 0; i < 4; i++) counts[17 + i] += (uint32_t)(current[i] - controlPrevious[i]);
    memcpy(controlPrevious, current, sizeof(current)); haveControl = 1;
    controlLoss = lossPercent; controlFlight = inFlight; lastControl = nowUs();
    pthread_mutex_unlock(&lock);
}

void NetDiagSnapshot(double out[32]) {
    pthread_mutex_lock(&lock);
    uint64_t now = nowUs();
    double seconds = (now - lastSnapshot > 0 ? now - lastSnapshot : 1) / 1000000.;
    for (int i = 0; i < 32; i++) out[i] = counts[i] / seconds;
    out[0] = counts[0] * 8. / seconds / 1000000.; out[1] = counts[1] * 8. / seconds / 1000000.;
    out[4] = counts[4] ? counts[4] / 1000. : NAN;
    out[5] = counts[5] ? counts[5] / 1000. : NAN;
    out[17] = haveControl ? counts[17] * 8. / seconds / 1000000. : NAN;
    out[18] = haveControl ? counts[18] * 8. / seconds / 1000000. : NAN;
    out[19] = haveControl ? counts[19] / seconds : NAN; out[20] = haveControl ? counts[20] / seconds : NAN;
    out[21] = haveControl ? controlLoss : NAN; out[22] = haveControl ? controlFlight : NAN;
    out[23] = lastControl ? (now - lastControl) / 1000. : NAN;
    out[24] = total[0]; out[25] = total[1]; out[26] = seconds * 1000;
    out[29] = lastPacket[0] ? (now - lastPacket[0]) / 1000. : NAN;
    out[30] = lastPacket[1] ? (now - lastPacket[1]) / 1000. : NAN;
    if (lastControl && now - lastControl > 2000000) for (int i = 17; i <= 22; i++) out[i] = NAN;
    memset(counts, 0, sizeof(counts)); lastSnapshot = now;
    pthread_mutex_unlock(&lock);
}
