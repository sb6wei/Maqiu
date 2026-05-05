package com.maqiu.cast.encoder;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import com.maqiu.cast.Constants;
import com.maqiu.cast.net.UdpSender;

import java.nio.ByteBuffer;

public class EncoderController {
    private final Context context;
    private final MediaProjection projection;

    private MediaCodec encoder;
    private VirtualDisplay virtualDisplay;
    private Surface inputSurface;

    private final BufferPool bufferPool;
    private final FramePool framePool;
    private final FrameQueue frameQueue;

    private Thread encoderThread;
    private Thread senderThread;
    private volatile boolean running;

    private byte[] sps;
    private byte[] pps;

    private UdpSender udpSender;
    private RtpPacketizer packetizer;
    private AbrController abrController;
    private Pacer pacer;

    private int width;
    private int height;
    private int densityDpi;

    public EncoderController(Context context, MediaProjection projection) {
        this.context = context;
        this.projection = projection;
        this.bufferPool = new BufferPool(Constants.FRAME_POOL_SIZE, Constants.MAX_ENCODED_FRAME_SIZE);
        this.framePool = new FramePool(Constants.FRAME_POOL_SIZE, bufferPool);
        this.frameQueue = new FrameQueue(Constants.MAX_FRAME_QUEUE, framePool);
    }

    public synchronized void startStreaming(String host, int rtpPort, int feedbackPort) {
        if (running) {
            return;
        }
        running = true;
        udpSender = new UdpSender(host, rtpPort);
        pacer = new Pacer(Constants.DEFAULT_BITRATE_KBPS);
        packetizer = new RtpPacketizer(udpSender, pacer);
        encoderThread = new Thread(() -> runEncoder(feedbackPort), "EncoderThread");
        senderThread = new Thread(this::runSender, "SenderThread");
        encoderThread.start();
        senderThread.start();
    }

    public synchronized void stopStreaming() {
        running = false;
        if (encoderThread != null) {
            encoderThread.interrupt();
        }
        if (senderThread != null) {
            senderThread.interrupt();
        }
        frameQueue.clear();
        releaseEncoder();
        if (udpSender != null) {
            udpSender.close();
            udpSender = null;
        }
    }

    private void runEncoder(int feedbackPort) {
        try {
            setupEncoder();
            abrController = new AbrController(encoder, Constants.DEFAULT_BITRATE_KBPS);
            abrController.start(feedbackPort);
            pacer.setTargetKbps(abrController.targetKbps());

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running) {
                int index = encoder.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = encoder.getOutputFormat();
                    sps = extractCsd(format, "csd-0");
                    pps = extractCsd(format, "csd-1");
                } else if (index >= 0) {
                    ByteBuffer buffer = encoder.getOutputBuffer(index);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        EncodedFrame frame = framePool.acquire();
                        buffer.get(frame.data, 0, info.size);
                        frame.length = info.size;
                        frame.ptsUs = info.presentationTimeUs;
                        frame.keyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        frameQueue.offer(frame);
                    }
                    encoder.releaseOutputBuffer(index, false);
                }
            }
        } catch (Exception e) {
            Log.e("MaqiuEncoder", "Encoder loop failed", e);
        } finally {
            if (abrController != null) {
                abrController.stop();
                abrController = null;
            }
        }
    }

    private void runSender() {
        while (running) {
            try {
                EncodedFrame frame = frameQueue.take(50);
                if (frame == null) {
                    continue;
                }
                long timestamp90k = frame.ptsUs * Constants.RTP_CLOCK_HZ / 1_000_000L;
                if (frame.keyFrame && sps != null && pps != null) {
                    sendNalUnit(sps, timestamp90k, false);
                    sendNalUnit(pps, timestamp90k, false);
                }
                sendAccessUnit(frame.data, frame.length, timestamp90k);
                framePool.release(frame);
                if (abrController != null) {
                    pacer.setTargetKbps(abrController.targetKbps());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sendAccessUnit(byte[] data, int length, long timestamp90k) {
        if (length < 4) {
            return;
        }
        if (isAnnexB(data)) {
            int offset = 0;
            while (offset < length) {
                int start = findStartCode(data, offset, length);
                if (start < 0) {
                    break;
                }
                int nalStart = start + 4;
                int next = findStartCode(data, nalStart, length);
                int nalEnd = next > 0 ? next : length;
                int nalLen = nalEnd - nalStart;
                if (nalLen > 0) {
                    packetizeNal(data, nalStart, nalLen, timestamp90k, nalEnd == length);
                }
                offset = nalEnd;
            }
        } else {
            int offset = 0;
            while (offset + 4 <= length) {
                int nalLen = ((data[offset] & 0xFF) << 24)
                        | ((data[offset + 1] & 0xFF) << 16)
                        | ((data[offset + 2] & 0xFF) << 8)
                        | (data[offset + 3] & 0xFF);
                offset += 4;
                if (nalLen <= 0 || offset + nalLen > length) {
                    break;
                }
                boolean last = offset + nalLen >= length;
                packetizeNal(data, offset, nalLen, timestamp90k, last);
                offset += nalLen;
            }
        }
    }

    private void sendNalUnit(byte[] nal, long timestamp90k, boolean marker) {
        packetizeNal(nal, 0, nal.length, timestamp90k, marker);
    }

    private void packetizeNal(byte[] data, int offset, int length, long timestamp90k, boolean marker) {
        packetizer.sendNal(data, offset, length, timestamp90k, marker);
    }

    private void setupEncoder() throws Exception {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = Math.min(1920, metrics.widthPixels);
        height = Math.min(1080, metrics.heightPixels);
        densityDpi = metrics.densityDpi;

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, Constants.DEFAULT_BITRATE_KBPS * 1000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, Constants.TARGET_FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = encoder.createInputSurface();
        encoder.start();

        virtualDisplay = projection.createVirtualDisplay(
                "MaqiuCast",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
        );
    }

    private void releaseEncoder() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Exception ignored) {
            }
            encoder.release();
            encoder = null;
        }
    }

    private byte[] extractCsd(MediaFormat format, String key) {
        if (!format.containsKey(key)) {
            return null;
        }
        ByteBuffer csd = format.getByteBuffer(key);
        if (csd == null) {
            return null;
        }
        byte[] data = new byte[csd.remaining()];
        csd.get(data);
        return stripStartCode(data);
    }

    private byte[] stripStartCode(byte[] data) {
        if (data.length > 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            byte[] out = new byte[data.length - 4];
            System.arraycopy(data, 4, out, 0, out.length);
            return out;
        }
        return data;
    }

    private boolean isAnnexB(byte[] data) {
        return data.length > 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1;
    }

    private int findStartCode(byte[] data, int offset, int limit) {
        for (int i = offset; i + 3 < limit; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                return i;
            }
        }
        return -1;
    }
}
