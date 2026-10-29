#pragma once
#include <stdint.h>
void NetDiagEnable(int enabled);
int NetDiagEnabled(void);
void NetDiagRtp(int audio, int length, uint16_t sequence);
void NetDiagAudio(const uint32_t counters[7]);
void NetDiagFec(uint32_t frame, uint8_t block, uint16_t data, uint16_t parity, uint16_t receivedData, uint16_t receivedParity);
void NetDiagControl(uint32_t sentBytes, uint32_t receivedBytes, uint32_t sentPackets, uint32_t receivedPackets, double lossPercent, uint32_t inFlight);
// Snapshot layout:
// 0..1 RTP Mbps; 2..3 RTP packets/s; 4..5 delivery-gap ms; 6 late/s; 7 duplicates/s;
// 8 repaired video packets/s; 9 failed video block reports/s; 10 reported missing data/s;
// 11..16 audio data/FEC/recovered/failed/late/invalid per second;
// 17..18 control UDP Mbps; 19..20 control UDP datagrams/s;
// 21 reliable loss estimate %; 22 reliable bytes in flight; 23 control sample age ms;
// 24..25 lifetime RTP counts; 26 interval ms; 27 invalid audio FEC/s;
// 28 recovered video block reports/s; 29..30 RTP idle age ms; 31 forward-gap hints/s.
void NetDiagSnapshot(double out[32]);
