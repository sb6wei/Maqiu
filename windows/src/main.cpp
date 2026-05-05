#include "discovery.h"
#include "receiver.h"

#include <windows.h>
#include <winsock2.h>

#include <string>
#include <vector>

namespace {
const int kButtonRefresh = 1001;
const int kButtonConnect = 1002;
const int kButtonStop = 1003;

HWND g_listBox = nullptr;
HWND g_status = nullptr;
std::vector<DeviceInfo> g_devices;
DiscoveryClient g_discovery;
DiscoveryResponder g_responder;
Receiver g_receiver;

void SetStatus(const std::string& text) {
    if (g_status) {
        SetWindowTextA(g_status, text.c_str());
    }
}

void RefreshDevices() {
    g_devices = g_discovery.discover();
    SendMessage(g_listBox, LB_RESETCONTENT, 0, 0);
    for (const auto& device : g_devices) {
        std::string label = device.name + " (" + device.ip + ")";
        SendMessageA(g_listBox, LB_ADDSTRING, 0, reinterpret_cast<LPARAM>(label.c_str()));
    }
    SetStatus("Discovery finished");
}

void ConnectSelected() {
    int idx = (int)SendMessage(g_listBox, LB_GETCURSEL, 0, 0);
    if (idx == LB_ERR || idx >= static_cast<int>(g_devices.size())) {
        SetStatus("Select a device first");
        return;
    }
    g_receiver.start(g_devices[idx]);
    SetStatus("Streaming...");
}

void StopReceiver() {
    g_receiver.stop();
    SetStatus("Stopped");
}
}

LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
        case WM_CREATE: {
            g_listBox = CreateWindowA("LISTBOX", nullptr, WS_CHILD | WS_VISIBLE | LBS_NOTIFY | WS_BORDER,
                                      20, 20, 320, 120, hwnd, nullptr, nullptr, nullptr);
            CreateWindowA("BUTTON", "Refresh", WS_CHILD | WS_VISIBLE,
                          360, 20, 120, 32, hwnd, (HMENU)kButtonRefresh, nullptr, nullptr);
            CreateWindowA("BUTTON", "Connect", WS_CHILD | WS_VISIBLE,
                          360, 60, 120, 32, hwnd, (HMENU)kButtonConnect, nullptr, nullptr);
            CreateWindowA("BUTTON", "Stop", WS_CHILD | WS_VISIBLE,
                          360, 100, 120, 32, hwnd, (HMENU)kButtonStop, nullptr, nullptr);
            g_status = CreateWindowA("STATIC", "Ready", WS_CHILD | WS_VISIBLE,
                                     20, 160, 460, 20, hwnd, nullptr, nullptr, nullptr);
            g_responder.start();
            break;
        }
        case WM_COMMAND: {
            int id = LOWORD(wParam);
            if (id == kButtonRefresh) {
                RefreshDevices();
            } else if (id == kButtonConnect) {
                ConnectSelected();
            } else if (id == kButtonStop) {
                StopReceiver();
            }
            break;
        }
        case WM_DESTROY:
            g_responder.stop();
            g_receiver.stop();
            PostQuitMessage(0);
            break;
        default:
            return DefWindowProc(hwnd, msg, wParam, lParam);
    }
    return 0;
}

int APIENTRY WinMain(HINSTANCE hInstance, HINSTANCE, LPSTR, int nCmdShow) {
    WSADATA wsaData;
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
        return 1;
    }

    const char* className = "MaqiuReceiver";
    WNDCLASSA wc{};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInstance;
    wc.lpszClassName = className;
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    RegisterClassA(&wc);

    HWND hwnd = CreateWindowA(className, "Maqiu Receiver",
                              WS_OVERLAPPEDWINDOW ^ WS_THICKFRAME,
                              CW_USEDEFAULT, CW_USEDEFAULT, 520, 260,
                              nullptr, nullptr, hInstance, nullptr);
    ShowWindow(hwnd, nCmdShow);

    MSG msg;
    while (GetMessage(&msg, nullptr, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

    WSACleanup();
    return 0;
}
