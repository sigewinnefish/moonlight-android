#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <stdio.h>
#include "touch_diagnostics.h"
#include "moonlight-common-c/src/Input.h"
static int result, calls;
static unsigned char packet[sizeof(SS_TOUCH_PACKET)];
int __real_sendInputPacketOnControlStream(unsigned char* data, int length, uint8_t channel, uint32_t flags, bool more) {
    assert(data == packet && length == sizeof(packet) && channel == 5 && flags == 77 && more);
    calls++;
    return result;
}
int __wrap_sendInputPacketOnControlStream(unsigned char*, int, uint8_t, uint32_t, bool);
static int handoff(void) {
    unsigned char copy[sizeof(packet)]; memcpy(copy, packet, sizeof(packet));
    int r = __wrap_sendInputPacketOnControlStream(packet, sizeof(packet), 5, 77, true);
    assert(memcmp(copy, packet, sizeof(packet)) == 0);
    return r;
}
int main(void) {
    uint64_t s[12];
    uint32_t magic = SS_TOUCH_MAGIC; memcpy(packet + 4, &magic, 4);
    packet[8] = 3; packet[12] = 7;
    TouchDiagEnable(0); assert(TouchDiagBegin(3, 7) == 0); assert(handoff() == 0);
    TouchDiagSnapshot(s); assert(s[0] == 0 && s[2] == 0 && s[5] == 0);
    TouchDiagEnable(1);
    uint64_t a = TouchDiagBegin(3, 7); assert(a);
    TouchDiagSnapshot(s); assert(s[0] == 1 && s[1] == 1 && s[5] == 1);
    struct timespec delay = {0, 5000000}; nanosleep(&delay, NULL);
    assert(handoff() == 0); TouchDiagComplete(a, 0);
    TouchDiagSnapshot(s); assert(s[2] == 1 && s[3] == 1 && s[4] >= 1000 && s[5] == 0);
    a = TouchDiagBegin(3, 7); uint64_t b = TouchDiagBegin(3, 7); assert(a != b);
    assert(handoff() == 0); TouchDiagSnapshot(s); assert(s[5] == 1);
    TouchDiagComplete(b, -1); TouchDiagSnapshot(s); assert(s[9] == 1 && s[5] == 0);
    TouchDiagBegin(3, 7); result = -9; assert(handoff() == -9);
    TouchDiagSnapshot(s); assert(s[8] == 1 && s[5] == 0);
    result = 0;
    for (int i = 0; i < 513; i++) TouchDiagBegin(3, 7);
    TouchDiagSnapshot(s); assert(s[5] == 512 && s[10] == 1);
    TouchDiagEnable(0); TouchDiagSnapshot(s); assert(s[5] == 0 && s[10] == 0);
    TouchDiagEnable(1);
    packet[4] = 0; assert(handoff() == 0); TouchDiagSnapshot(s); assert(s[2] == 0 && s[11] == 1);
    assert(calls == 5);
    puts("PASS: native touch queue tracking, FIFO, failures, overflow, disabled/non-touch passthrough and unchanged arguments");
}
