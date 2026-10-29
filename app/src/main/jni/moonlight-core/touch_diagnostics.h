#pragma once
#include <stdint.h>

void TouchDiagEnable(int enabled);
uint64_t TouchDiagBegin(uint8_t type, uint32_t id);
void TouchDiagComplete(uint64_t token, int result);
// Interval counts plus current queue state. No coordinates or application content are recorded.
void TouchDiagSnapshot(uint64_t out[12]);
