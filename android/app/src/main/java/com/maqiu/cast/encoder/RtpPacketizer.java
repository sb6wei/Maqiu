package com.maqiu.cast.encoder;

import com.maqiu.cast.Constants;
import com.maqiu.cast.net.UdpSender;

public class RtpPacketizer {
    private final UdpSender sender;
    private final Pacer pacer;
    private final byte[] packetBuffer = new byte[Constants.MAX_PACKET_SIZE];
    private int sequence = 1;
    private final int ssrc = (int) (System.nanoTime() & 0x7fffffff);
    private static final int RTP_HEADER_SIZE = 12;

    public RtpPacketizer(UdpSender sender, Pacer pacer) {
        this.sender = sender;
        this.pacer = pacer;
    }

    public void sendNal(byte[] nal, int offset, int length, long timestamp90k, boolean marker) {
        int maxPayload = Constants.MAX_PACKET_SIZE - RTP_HEADER_SIZE;
        if (length <= maxPayload) {
            int payloadLen = length;
            buildHeader(packetBuffer, timestamp90k, marker, sequence++);
            System.arraycopy(nal, offset, packetBuffer, RTP_HEADER_SIZE, payloadLen);
            sender.send(packetBuffer, RTP_HEADER_SIZE + payloadLen);
            pacer.pace(RTP_HEADER_SIZE + payloadLen);
        } else {
            int nalHeader = nal[offset] & 0xFF;
            int nalType = nalHeader & 0x1F;
            int fuIndicator = (nalHeader & 0xE0) | 28;
            int fuHeaderStart = 0x80 | nalType;
            int fuHeaderMiddle = nalType;
            int fuHeaderEnd = 0x40 | nalType;

            int payloadOffset = offset + 1;
            int payloadRemaining = length - 1;
            boolean first = true;
            while (payloadRemaining > 0) {
                int chunk = Math.min(payloadRemaining, maxPayload - 2);
                boolean last = payloadRemaining - chunk == 0;
                buildHeader(packetBuffer, timestamp90k, last && marker, sequence++);
                packetBuffer[RTP_HEADER_SIZE] = (byte) fuIndicator;
                packetBuffer[RTP_HEADER_SIZE + 1] = (byte) (first ? fuHeaderStart : (last ? fuHeaderEnd : fuHeaderMiddle));
                System.arraycopy(nal, payloadOffset, packetBuffer, RTP_HEADER_SIZE + 2, chunk);
                sender.send(packetBuffer, RTP_HEADER_SIZE + 2 + chunk);
                pacer.pace(RTP_HEADER_SIZE + 2 + chunk);
                payloadOffset += chunk;
                payloadRemaining -= chunk;
                first = false;
            }
        }
    }

    private void buildHeader(byte[] buffer, long timestamp90k, boolean marker, int seq) {
        buffer[0] = (byte) 0x80;
        buffer[1] = (byte) ((marker ? 0x80 : 0x00) | 96);
        buffer[2] = (byte) (seq >> 8);
        buffer[3] = (byte) (seq);
        buffer[4] = (byte) (timestamp90k >> 24);
        buffer[5] = (byte) (timestamp90k >> 16);
        buffer[6] = (byte) (timestamp90k >> 8);
        buffer[7] = (byte) (timestamp90k);
        buffer[8] = (byte) (ssrc >> 24);
        buffer[9] = (byte) (ssrc >> 16);
        buffer[10] = (byte) (ssrc >> 8);
        buffer[11] = (byte) (ssrc);
    }
}
