#include "receiver.h"

#include <winsock2.h>
#include <ws2tcpip.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libswscale/swscale.h>
}

#include <SDL.h>

#include <chrono>
#include <iostream>

namespace {
constexpr int kLocalRtpPort = 50002;
constexpr int kLossFeedbackIntervalMs = 1000;

std::string getLocalIp(const std::string& remoteIp) {
    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock == INVALID_SOCKET) {
        return "";
    }
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(9);
    inet_pton(AF_INET, remoteIp.c_str(), &addr.sin_addr);
    connect(sock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    sockaddr_in local{};
    int len = sizeof(local);
    getsockname(sock, reinterpret_cast<sockaddr*>(&local), &len);
    char buffer[INET_ADDRSTRLEN] = {0};
    inet_ntop(AF_INET, &local.sin_addr, buffer, sizeof(buffer));
    closesocket(sock);
    return buffer;
}

uint64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}
}

class FrameAssembler {
public:
    FrameAssembler() {
        buffer_.reserve(2 * 1024 * 1024);
    }

    bool consume(const RtpPacket& packet, bool lost, EncodedFrame& outFrame) {
        if (lost) {
            onPacketLoss();
            return false;
        }
        if (!hasTimestamp_) {
            timestamp_ = packet.timestamp;
            hasTimestamp_ = true;
        }
        if (packet.timestamp != timestamp_ && !buffer_.empty()) {
            onPacketLoss();
        }
        if (!hasTimestamp_) {
            timestamp_ = packet.timestamp;
            hasTimestamp_ = true;
        } else if (packet.timestamp != timestamp_) {
            timestamp_ = packet.timestamp;
        }

        if (packet.payloadLen <= 0) {
            return false;
        }
        const uint8_t* payload = packet.payload;
        int len = packet.payloadLen;
        uint8_t nalType = payload[0] & 0x1F;
        if (nalType > 0 && nalType < 24) {
            appendStartCode();
            buffer_.insert(buffer_.end(), payload, payload + len);
            if (nalType == 5) {
                currentKeyframe_ = true;
            }
        } else if (nalType == 28 && len > 2) {
            uint8_t fuHeader = payload[1];
            bool start = (fuHeader & 0x80) != 0;
            bool end = (fuHeader & 0x40) != 0;
            uint8_t nal = (payload[0] & 0xE0) | (fuHeader & 0x1F);
            if (start) {
                appendStartCode();
                buffer_.push_back(nal);
                inFragment_ = true;
            }
            if (inFragment_) {
                buffer_.insert(buffer_.end(), payload + 2, payload + len);
            }
            if (end) {
                inFragment_ = false;
                if ((nal & 0x1F) == 5) {
                    currentKeyframe_ = true;
                }
            }
        }

        if (packet.marker) {
            return finalize(outFrame);
        }
        return false;
    }

    void onPacketLoss() {
        needKeyframe_ = true;
        buffer_.clear();
        hasTimestamp_ = false;
        inFragment_ = false;
    }

private:
    void appendStartCode() {
        static const uint8_t startCode[] = {0, 0, 0, 1};
        buffer_.insert(buffer_.end(), std::begin(startCode), std::end(startCode));
    }

    bool finalize(EncodedFrame& outFrame) {
        if (buffer_.empty()) {
            resetState();
            return false;
        }
        if (needKeyframe_ && !currentKeyframe_) {
            resetState();
            return false;
        }
        outFrame.data = buffer_;
        outFrame.key = currentKeyframe_;
        outFrame.timestamp = timestamp_;
        if (currentKeyframe_) {
            needKeyframe_ = false;
        }
        resetState();
        return true;
    }

    void resetState() {
        buffer_.clear();
        currentKeyframe_ = false;
        hasTimestamp_ = false;
    }

    std::vector<uint8_t> buffer_;
    uint32_t timestamp_ = 0;
    bool hasTimestamp_ = false;
    bool currentKeyframe_ = false;
    bool needKeyframe_ = false;
    bool inFragment_ = false;
};

Receiver::Receiver() = default;

Receiver::~Receiver() {
    stop();
}

bool Receiver::start(const DeviceInfo& device) {
    if (running_) {
        return false;
    }
    device_ = device;
    running_ = true;
    sendStartCommand();
    recvThread_ = std::thread(&Receiver::receiveLoop, this);
    decodeThread_ = std::thread(&Receiver::decodeLoop, this);
    renderThread_ = std::thread(&Receiver::renderLoop, this);
    return true;
}

void Receiver::stop() {
    running_ = false;
    decodeQueue_.clear();
    renderQueue_.clear();
    if (recvThread_.joinable()) {
        recvThread_.join();
    }
    if (decodeThread_.joinable()) {
        decodeThread_.join();
    }
    if (renderThread_.joinable()) {
        renderThread_.join();
    }
}

void Receiver::sendStartCommand() {
    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock == INVALID_SOCKET) {
        return;
    }
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(device_.controlPort);
    inet_pton(AF_INET, device_.ip.c_str(), &addr.sin_addr);

    std::string localIp = getLocalIp(device_.ip);
    std::string cmd = "MAQIU_START|" + localIp + "|" + std::to_string(kLocalRtpPort) + "|" +
                      std::to_string(device_.feedbackPort);
    sendto(sock, cmd.c_str(), static_cast<int>(cmd.size()), 0, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    closesocket(sock);
}

void Receiver::receiveLoop() {
    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock == INVALID_SOCKET) {
        running_ = false;
        return;
    }
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(kLocalRtpPort);
    addr.sin_addr.s_addr = INADDR_ANY;
    if (bind(sock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        closesocket(sock);
        running_ = false;
        return;
    }

    JitterBuffer jitter(512);
    FrameAssembler assembler;
    uint64_t lastFeedback = nowMs();
    int received = 0;
    int lostCount = 0;
    bool startedBuffering = false;
    uint64_t startBufferTime = nowMs();

    while (running_) {
        uint8_t buffer[1500];
        sockaddr_in from{};
        int fromLen = sizeof(from);
        int len = recvfrom(sock, reinterpret_cast<char*>(buffer), sizeof(buffer), 0,
                           reinterpret_cast<sockaddr*>(&from), &fromLen);
        if (len <= 0) {
            continue;
        }
        if (len < 12) {
            continue;
        }
        uint8_t v = buffer[0] >> 6;
        if (v != 2) {
            continue;
        }
        int cc = buffer[0] & 0x0F;
        int headerLen = 12 + cc * 4;
        if (len <= headerLen) {
            continue;
        }
        RtpPacket packet;
        packet.marker = (buffer[1] & 0x80) != 0;
        packet.payloadType = buffer[1] & 0x7F;
        packet.seq = (buffer[2] << 8) | buffer[3];
        packet.timestamp = (buffer[4] << 24) | (buffer[5] << 16) | (buffer[6] << 8) | buffer[7];
        packet.payloadLen = len - headerLen;
        memcpy(packet.payload, buffer + headerLen, packet.payloadLen);
        jitter.push(packet);

        if (!startedBuffering && nowMs() - startBufferTime > 50) {
            startedBuffering = true;
        }
        if (!startedBuffering) {
            continue;
        }

        bool lost = false;
        RtpPacket out;
        while (jitter.pop(out, lost)) {
            if (lost) {
                lostCount++;
                assembler.onPacketLoss();
                continue;
            }
            received++;
            EncodedFrame frame;
            if (assembler.consume(out, false, frame)) {
                decodeQueue_.push(std::move(frame));
            }
        }

        uint64_t now = nowMs();
        if (now - lastFeedback > kLossFeedbackIntervalMs) {
            int total = received + lostCount;
            int lossPercent = total > 0 ? (lostCount * 100 / total) : 0;
            sendLossFeedback(lossPercent);
            received = 0;
            lostCount = 0;
            lastFeedback = now;
        }
    }
    closesocket(sock);
}

void Receiver::decodeLoop() {
    const AVCodec* codec = avcodec_find_decoder(AV_CODEC_ID_H264);
    if (!codec) {
        running_ = false;
        return;
    }
    AVCodecContext* ctx = avcodec_alloc_context3(codec);
    if (!ctx) {
        running_ = false;
        return;
    }
    avcodec_open2(ctx, codec, nullptr);
    codecCtx_ = ctx;

    AVPacket* pkt = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();
    while (running_) {
        EncodedFrame encoded;
        if (!decodeQueue_.pop(encoded, 50)) {
            continue;
        }
        av_packet_unref(pkt);
        if (av_new_packet(pkt, static_cast<int>(encoded.data.size())) != 0) {
            continue;
        }
        memcpy(pkt->data, encoded.data.data(), encoded.data.size());
        int ret = avcodec_send_packet(ctx, pkt);
        if (ret == AVERROR(EAGAIN)) {
            while (avcodec_receive_frame(ctx, frame) == 0) {
                AVFrame* out = av_frame_alloc();
                av_frame_ref(out, frame);
                renderQueue_.push(out);
                av_frame_unref(frame);
            }
            continue;
        }
        while (true) {
            ret = avcodec_receive_frame(ctx, frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                break;
            }
            AVFrame* out = av_frame_alloc();
            av_frame_ref(out, frame);
            renderQueue_.push(out);
            av_frame_unref(frame);
        }
    }
    av_frame_free(&frame);
    av_packet_free(&pkt);
    avcodec_free_context(&ctx);
    codecCtx_ = nullptr;
}

void Receiver::renderLoop() {
    if (SDL_Init(SDL_INIT_VIDEO) != 0) {
        running_ = false;
        return;
    }
    SDL_Window* window = nullptr;
    SDL_Renderer* renderer = nullptr;
    SDL_Texture* texture = nullptr;
    SwsContext* sws = nullptr;
    AVFrame* swsFrame = av_frame_alloc();
    uint8_t* swsBuffer = nullptr;

    while (running_) {
        AVFrame* frame = nullptr;
        if (!renderQueue_.pop(frame, 50)) {
            continue;
        }
        if (!frame) {
            continue;
        }
        if (!window) {
            window = SDL_CreateWindow("Maqiu Receiver", SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
                                      frame->width, frame->height, SDL_WINDOW_SHOWN);
            renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC);
        }
        if (!texture || frame->width != swsFrame->width || frame->height != swsFrame->height) {
            if (texture) {
                SDL_DestroyTexture(texture);
            }
            texture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_IYUV, SDL_TEXTUREACCESS_STREAMING,
                                        frame->width, frame->height);
            if (swsBuffer) {
                av_free(swsBuffer);
            }
            swsFrame->format = AV_PIX_FMT_YUV420P;
            swsFrame->width = frame->width;
            swsFrame->height = frame->height;
            int bufferSize = av_image_alloc(swsFrame->data, swsFrame->linesize, frame->width, frame->height,
                                            AV_PIX_FMT_YUV420P, 1);
            swsBuffer = swsFrame->data[0];
            if (sws) {
                sws_freeContext(sws);
            }
            sws = sws_getContext(frame->width, frame->height, static_cast<AVPixelFormat>(frame->format),
                                 frame->width, frame->height, AV_PIX_FMT_YUV420P, SWS_BILINEAR, nullptr, nullptr, nullptr);
            (void)bufferSize;
        }

        AVFrame* displayFrame = frame;
        if (frame->format != AV_PIX_FMT_YUV420P) {
            sws_scale(sws, frame->data, frame->linesize, 0, frame->height, swsFrame->data, swsFrame->linesize);
            displayFrame = swsFrame;
        }
        SDL_UpdateYUVTexture(texture, nullptr,
                             displayFrame->data[0], displayFrame->linesize[0],
                             displayFrame->data[1], displayFrame->linesize[1],
                             displayFrame->data[2], displayFrame->linesize[2]);
        SDL_RenderClear(renderer);
        SDL_RenderCopy(renderer, texture, nullptr, nullptr);
        SDL_RenderPresent(renderer);

        SDL_Event event;
        while (SDL_PollEvent(&event)) {
            if (event.type == SDL_QUIT) {
                running_ = false;
            }
        }

        av_frame_free(&frame);
    }

    if (texture) {
        SDL_DestroyTexture(texture);
    }
    if (renderer) {
        SDL_DestroyRenderer(renderer);
    }
    if (window) {
        SDL_DestroyWindow(window);
    }
    if (sws) {
        sws_freeContext(sws);
    }
    if (swsFrame) {
        av_freep(&swsFrame->data[0]);
        av_frame_free(&swsFrame);
    }
    SDL_Quit();
}

void Receiver::sendLossFeedback(int lossPercent) {
    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock == INVALID_SOCKET) {
        return;
    }
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(device_.feedbackPort);
    inet_pton(AF_INET, device_.ip.c_str(), &addr.sin_addr);
    std::string msg = "LOSS|" + std::to_string(lossPercent);
    sendto(sock, msg.c_str(), static_cast<int>(msg.size()), 0, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    closesocket(sock);
}
