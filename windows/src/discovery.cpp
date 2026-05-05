#include "discovery.h"

#include <winsock2.h>
#include <ws2tcpip.h>
#include <chrono>
#include <cstring>
#include <sstream>

namespace {
constexpr int kDiscoveryPort = 50000;

std::vector<std::string> split(const std::string& input, char delim) {
    std::vector<std::string> parts;
    std::stringstream ss(input);
    std::string item;
    while (std::getline(ss, item, delim)) {
        parts.push_back(item);
    }
    return parts;
}
}

std::vector<DeviceInfo> DiscoveryClient::discover() {
    std::vector<DeviceInfo> devices;
    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock == INVALID_SOCKET) {
        return devices;
    }
    BOOL broadcast = TRUE;
    setsockopt(sock, SOL_SOCKET, SO_BROADCAST, reinterpret_cast<const char*>(&broadcast), sizeof(broadcast));
    timeval timeout{1, 0};
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeout), sizeof(timeout));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(kDiscoveryPort);
    addr.sin_addr.s_addr = INADDR_BROADCAST;

    const char* msg = "MAQIU_DISCOVER";
    sendto(sock, msg, static_cast<int>(strlen(msg)), 0, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));

    auto start = std::chrono::steady_clock::now();
    char buffer[256];
    sockaddr_in from{};
    int fromLen = sizeof(from);
    while (std::chrono::steady_clock::now() - start < std::chrono::milliseconds(800)) {
        int len = recvfrom(sock, buffer, sizeof(buffer) - 1, 0, reinterpret_cast<sockaddr*>(&from), &fromLen);
        if (len <= 0) {
            continue;
        }
        buffer[len] = '\0';
        std::string msgStr(buffer);
        if (msgStr.rfind("MAQIU_DEVICE|", 0) == 0) {
            auto parts = split(msgStr, '|');
            if (parts.size() >= 5) {
                DeviceInfo info;
                info.name = parts[1];
                char ip[INET_ADDRSTRLEN] = {0};
                inet_ntop(AF_INET, &from.sin_addr, ip, sizeof(ip));
                info.ip = ip;
                info.rtpPort = std::stoi(parts[2]);
                info.feedbackPort = std::stoi(parts[3]);
                info.controlPort = std::stoi(parts[4]);
                devices.push_back(info);
            }
        }
    }
    closesocket(sock);
    return devices;
}

DiscoveryResponder::DiscoveryResponder() = default;

DiscoveryResponder::~DiscoveryResponder() {
    stop();
}

void DiscoveryResponder::start() {
    if (running_) {
        return;
    }
    running_ = true;
    thread_ = std::thread([this]() { run(); });
}

void DiscoveryResponder::stop() {
    running_ = false;
    if (thread_.joinable()) {
        thread_.join();
    }
}

void DiscoveryResponder::run() {
    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock == INVALID_SOCKET) {
        return;
    }
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(kDiscoveryPort);
    addr.sin_addr.s_addr = INADDR_ANY;
    if (bind(sock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        closesocket(sock);
        return;
    }

    char buffer[256];
    while (running_) {
        sockaddr_in from{};
        int fromLen = sizeof(from);
        int len = recvfrom(sock, buffer, sizeof(buffer) - 1, 0, reinterpret_cast<sockaddr*>(&from), &fromLen);
        if (len <= 0) {
            continue;
        }
        buffer[len] = '\0';
        if (strcmp(buffer, "MAQIU_DISCOVER") == 0) {
            std::string response = "MAQIU_RECEIVER|Windows";
            sendto(sock, response.c_str(), static_cast<int>(response.size()), 0,
                   reinterpret_cast<sockaddr*>(&from), fromLen);
        }
    }
    closesocket(sock);
}
