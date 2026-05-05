#pragma once

#include "discovery.h"
#include "frame_queue.h"
#include "jitter_buffer.h"

#include <atomic>
#include <thread>
#include <vector>

struct AVFrame;

struct EncodedFrame {
    std::vector<uint8_t> data;
    bool key = false;
    uint32_t timestamp = 0;
};

class Receiver {
public:
    Receiver();
    ~Receiver();

    bool start(const DeviceInfo& device);
    void stop();

private:
    void receiveLoop();
    void decodeLoop();
    void renderLoop();
    void sendStartCommand();
    void sendStopCommand();
    void sendLossFeedback(int lossPercent);
    void drainRenderQueue();

    DeviceInfo device_;

    std::atomic<bool> running_{false};
    std::thread recvThread_;
    std::thread decodeThread_;
    std::thread renderThread_;

    FrameQueue<EncodedFrame> decodeQueue_{8};
    FrameQueue<AVFrame*> renderQueue_{8};

    void* codecCtx_ = nullptr;
};
