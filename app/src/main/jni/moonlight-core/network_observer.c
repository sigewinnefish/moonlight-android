#include "moonlight-common-c/src/Limelight-internal.h"
#include "network_diagnostics.h"

int __real_RtpvAddPacket(PRTP_VIDEO_QUEUE, PRTP_PACKET, int, PRTPV_QUEUE_ENTRY);
int __wrap_RtpvAddPacket(PRTP_VIDEO_QUEUE queue, PRTP_PACKET packet, int length, PRTPV_QUEUE_ENTRY entry) {
    NetDiagRtp(0, length, packet->sequenceNumber);
    return __real_RtpvAddPacket(queue, packet, length, entry);
}

int __real_RtpaAddPacket(PRTP_AUDIO_QUEUE, PRTP_PACKET, uint16_t);
int __wrap_RtpaAddPacket(PRTP_AUDIO_QUEUE queue, PRTP_PACKET packet, uint16_t length) {
    NetDiagRtp(1, length, packet->sequenceNumber);
    int result = __real_RtpaAddPacket(queue, packet, length);
    if (NetDiagEnabled()) {
        const RTP_AUDIO_STATS* s = &queue->stats;
        const uint32_t counters[7] = {s->packetCountAudio, s->packetCountFec, s->packetCountFecRecovered,
                s->packetCountFecFailed, s->packetCountOOS, s->packetCountInvalid, s->packetCountFecInvalid};
        NetDiagAudio(counters);
    }
    return result;
}

void __real_connectionSendFrameFecStatus(PSS_FRAME_FEC_STATUS);
void __wrap_connectionSendFrameFecStatus(PSS_FRAME_FEC_STATUS status) {
    NetDiagFec(BE32(status->frameIndex), status->multiFecBlockIndex,
            BE16(status->totalDataPackets), BE16(status->totalParityPackets),
            BE16(status->receivedDataPackets), BE16(status->receivedParityPackets));
    __real_connectionSendFrameFecStatus(status);
}

int __real_enet_host_service(ENetHost*, ENetEvent*, enet_uint32);
int __wrap_enet_host_service(ENetHost* host, ENetEvent* event, enet_uint32 timeout) {
    int result = __real_enet_host_service(host, event, timeout);
    // ControlStream owns one peer; inspect while the caller still owns its ENet mutex.
    // No host/peer pointer survives this call, and no additional transport lock is acquired.
    if (NetDiagEnabled() && host->peerCount == 1 && host->peers[0].state == ENET_PEER_STATE_CONNECTED) {
        NetDiagControl(host->totalSentData, host->totalReceivedData,
                host->totalSentPackets, host->totalReceivedPackets,
                host->peers[0].packetLoss * 100. / ENET_PEER_PACKET_LOSS_SCALE,
                host->peers[0].reliableDataInTransit);
    }
    return result;
}
