#pragma once

#include <string>
#include <vector>
#include <thread>
#include <atomic>

struct DeviceInfo {
    std::string name;
    std::string ip;
    int rtpPort = 50002;
    int feedbackPort = 50003;
    int controlPort = 50001;
};

class DiscoveryClient {
public:
    std::vector<DeviceInfo> discover();
};

class DiscoveryResponder {
public:
    DiscoveryResponder();
    ~DiscoveryResponder();

    void start();
    void stop();

private:
    void run();

    std::thread thread_;
    std::atomic<bool> running_{false};
};
