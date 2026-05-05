package com.maqiu.cast;

public final class Constants {
    public static final int DISCOVERY_PORT = 50000;
    public static final int CONTROL_PORT = 50001;
    public static final int RTP_PORT = 50002;
    public static final int FEEDBACK_PORT = 50003;
    public static final int MAX_PACKET_SIZE = 1400;
    public static final int RTP_CLOCK_HZ = 90000;
    public static final int MAX_FRAME_QUEUE = 8;
    public static final int MAX_ENCODED_FRAME_SIZE = 2 * 1024 * 1024;
    public static final int FRAME_POOL_SIZE = 12;
    public static final int PACKET_POOL_SIZE = 64;
    public static final int DEFAULT_BITRATE_KBPS = 8000;
    public static final int MIN_BITRATE_KBPS = 3000;
    public static final int MAX_BITRATE_KBPS = 12000;

    private Constants() {
    }
}
