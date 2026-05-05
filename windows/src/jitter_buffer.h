#pragma once

#include <cstdint>
#include <map>
#include <mutex>
#include <chrono>

struct RtpPacket {
    uint16_t seq = 0;
    uint32_t timestamp = 0;
    bool marker = false;
    uint8_t payloadType = 0;
    int payloadLen = 0;
    uint8_t payload[1500];
};

class JitterBuffer {
public:
    explicit JitterBuffer(size_t maxPackets) : maxPackets_(maxPackets) {}

    void push(const RtpPacket& packet) {
        std::lock_guard<std::mutex> lock(mutex_);
        packets_.emplace(packet.seq, packet);
        if (!hasExpected_) {
            expectedSeq_ = packet.seq;
            hasExpected_ = true;
        }
    }

    bool pop(RtpPacket& out, bool& lost) {
        std::lock_guard<std::mutex> lock(mutex_);
        lost = false;
        if (!hasExpected_) {
            return false;
        }
        auto it = packets_.find(expectedSeq_);
        if (it != packets_.end()) {
            out = it->second;
            packets_.erase(it);
            expectedSeq_++;
            lastPopMs_ = nowMs();
            return true;
        }
        uint64_t now = nowMs();
        if (lastPopMs_ == 0) {
            lastPopMs_ = now;
        }
        if (packets_.size() > maxPackets_ || (now - lastPopMs_ > maxDelayMs_ && !packets_.empty())) {
            expectedSeq_++;
            lastPopMs_ = now;
            lost = true;
            return true;
        }
        return false;
    }

    size_t size() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return packets_.size();
    }

    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        packets_.clear();
        hasExpected_ = false;
    }

private:
    static uint64_t nowMs() {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
    }

    mutable std::mutex mutex_;
    std::map<uint16_t, RtpPacket> packets_;
    size_t maxPackets_ = 256;
    uint16_t expectedSeq_ = 0;
    bool hasExpected_ = false;
    uint64_t lastPopMs_ = 0;
    uint64_t maxDelayMs_ = 50;
};
