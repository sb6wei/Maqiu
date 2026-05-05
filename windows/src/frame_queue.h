#pragma once

#include <condition_variable>
#include <deque>
#include <mutex>

template <typename T>
class FrameQueue {
public:
    explicit FrameQueue(size_t capacity) : capacity_(capacity) {}

    void push(T item) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (queue_.size() >= capacity_) {
            queue_.pop_front();
        }
        queue_.push_back(std::move(item));
        cv_.notify_one();
    }

    bool pop(T& out, int timeoutMs) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (queue_.empty()) {
            if (cv_.wait_for(lock, std::chrono::milliseconds(timeoutMs)) == std::cv_status::timeout) {
                return false;
            }
        }
        if (queue_.empty()) {
            return false;
        }
        out = std::move(queue_.front());
        queue_.pop_front();
        return true;
    }

    void clear() {
        std::unique_lock<std::mutex> lock(mutex_);
        queue_.clear();
    }

private:
    size_t capacity_;
    std::deque<T> queue_;
    std::mutex mutex_;
    std::condition_variable cv_;
};
