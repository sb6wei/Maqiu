package com.maqiu.cast.net;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class UdpSender {
    private final DatagramSocket socket;
    private final InetAddress address;
    private final int port;

    public UdpSender(String host, int port) {
        try {
            this.socket = new DatagramSocket();
            this.address = InetAddress.getByName(host);
            this.port = port;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void send(byte[] data, int length) {
        try {
            DatagramPacket packet = new DatagramPacket(data, length, address, port);
            socket.send(packet);
        } catch (Exception ignored) {
        }
    }

    public void close() {
        socket.close();
    }
}
