package com.maqiu.cast.encoder;

public class EncodedFrame {
    public byte[] data;
    public int length;
    public long ptsUs;
    public boolean keyFrame;

    public void reset() {
        length = 0;
        ptsUs = 0;
        keyFrame = false;
    }
}
