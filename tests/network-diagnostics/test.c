#include <assert.h>
#include <math.h>
#include <stdint.h>
#include <pthread.h>
#include <time.h>
#include <stdio.h>
#include "network_diagnostics.h"

static void count(double sample[32], int index, double expected) {
    assert(fabs(sample[index] * sample[26] / 1000. - expected) < .001);
}
static void* audioThread(void* unused) {
    (void)unused;
    for (int i = 0; i < 1000; i++) NetDiagRtp(1, 200, i);
    return NULL;
}
int main(void) {
    double s[32];
    NetDiagEnable(0); NetDiagRtp(0, 100, 1); NetDiagSnapshot(s);
    assert(s[2] == 0 && isnan(s[21]));
    NetDiagEnable(1);
    NetDiagRtp(0, 1000, 65534);
    struct timespec pause = {0, 2000000}; nanosleep(&pause, NULL);
    NetDiagRtp(0, 1000, 0); // Wrap, with one hole.
    NetDiagRtp(0, 1000, 65535); // Late arrival fills it; the forward-gap hint remains a hint.
    NetDiagRtp(0, 1000, 65535); NetDiagRtp(0, 1000, 0); // Two duplicates.
    NetDiagRtp(1, 200, 1); NetDiagRtp(1, 200, 1); // Audio FEC has another sequence space.
    uint32_t audio0[7] = {100, 10, 4, 2, 1, 0, 0}, audio1[7] = {104, 12, 6, 3, 2, 1, 1};
    NetDiagAudio(audio0); NetDiagAudio(audio1);
    NetDiagFec(1, 0, 10, 3, 8, 2); NetDiagFec(1, 0, 10, 3, 8, 2); // Deduplicated report.
    NetDiagFec(2, 0, 10, 3, 5, 2);
    NetDiagFec(3, 0, 1, 0, 0, 2); // Invalid parity counts must not invent a recovery.
    NetDiagControl(UINT32_MAX-5, 100, UINT32_MAX-2, 6, 0, 0);
    NetDiagControl(4, 110, 2, 8, 1.25, 4096);
    NetDiagSnapshot(s);
    count(s, 2, 5); count(s, 3, 2); count(s, 6, 2); count(s, 7, 2); count(s, 31, 1);
    assert(s[4] >= 1 && s[24] == 5 && s[25] == 2);
    assert(fabs(s[0] * s[26] / 1000. - .04) < .000001);
    count(s, 8, 2); count(s, 9, 1); count(s, 10, 7); count(s, 28, 1);
    count(s, 11, 4); count(s, 12, 2); count(s, 13, 2); count(s, 14, 1);
    count(s, 15, 1); count(s, 16, 1); count(s, 27, 1);
    assert(fabs(s[17] * s[26] / 1000. - .00008) < .000001);
    count(s, 19, 5); count(s, 20, 2); assert(s[21] == 1.25 && s[22] == 4096);
    NetDiagSnapshot(s); assert(s[2] == 0 && s[8] == 0 && s[24] == 5 && isnan(s[4]));
    NetDiagEnable(1); NetDiagRtp(0, 100, 0); NetDiagSnapshot(s);
    count(s, 2, 1); assert(s[24] == 1 && s[7] == 0 && isnan(s[17]));
    uint32_t wrap0[7] = {UINT32_MAX, 0, 0, 0, 0, 0, 0}, wrap1[7] = {1, 0, 0, 0, 0, 0, 0};
    NetDiagAudio(wrap0); NetDiagAudio(wrap1); NetDiagSnapshot(s); count(s, 11, 2);
    NetDiagControl(1, 1, 1, 1, 1, 1);
    struct timespec stale = {2, 100000000}; nanosleep(&stale, NULL);
    NetDiagSnapshot(s); assert(isnan(s[17]) && isnan(s[21]) && s[23] > 2000);
    NetDiagEnable(1);
    pthread_t audio; assert(pthread_create(&audio, NULL, audioThread, NULL) == 0);
    for (int i = 0; i < 10000; i++) NetDiagRtp(0, 100, i);
    assert(pthread_join(audio, NULL) == 0); NetDiagSnapshot(s);
    count(s, 2, 10000); count(s, 3, 1000); assert(s[6] == 0 && s[7] == 0);
    puts("PASS: RTP rates, gaps, wrap/late/duplicate semantics, FEC report deduplication, audio/control counter wrap, reset, missing data and concurrent streams");
}
