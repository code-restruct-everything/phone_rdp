#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <deque>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

extern "C" {
#include <winpr/synch.h>
#include <winpr/wlog.h>

#include <freerdp/client/cmdline.h>
#include <freerdp/error.h>
#include <freerdp/freerdp.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/input.h>
#include <freerdp/settings.h>
#include <freerdp/settings_keys.h>
}

namespace {
constexpr const char* kLogTag = "RdpBridge";

constexpr jint RC_OK = 0;
constexpr jint RC_ALREADY_CONNECTED = 1;
constexpr jint RC_NOT_CONNECTED = 2;

constexpr jint RC_INVALID_ARGUMENT = -1101;
constexpr jint RC_NETWORK_FAILURE = -1201;
constexpr jint RC_AUTH_FAILURE = -1301;
constexpr jint RC_TLS_FAILURE = -1401;
constexpr jint RC_CERTIFICATE_REJECTED = -1402;
constexpr jint RC_BACKEND_UNAVAILABLE = -1501;
constexpr jint RC_FRAME_UNAVAILABLE = -1601;
constexpr jint RC_INPUT_FAILURE = -1701;
constexpr jint RC_INTERNAL_ERROR = -1999;

constexpr jint POINTER_ACTION_MOVE = 0;
constexpr jint POINTER_ACTION_LEFT_CLICK = 1;
constexpr jint POINTER_ACTION_RIGHT_CLICK = 2;
constexpr jint POINTER_ACTION_SCROLL = 3;

constexpr DWORD kMaxWaitHandles = 64;
constexpr UINT32 kDefaultDesktopWidth = 1280;
constexpr UINT32 kDefaultDesktopHeight = 720;
constexpr UINT32 kDefaultColorDepth = 32;

struct RdpSession;

struct PhoneRdpContext {
    rdpContext context;
    RdpSession* session = nullptr;
};

std::mutex g_stateMutex;
bool g_initialized = false;
std::unique_ptr<RdpSession> g_session;

std::mutex g_instanceMapMutex;
std::unordered_map<freerdp*, RdpSession*> g_instanceMap;
std::atomic<int64_t> g_certificateRequestCounter{1};
std::atomic<bool> g_winprLogConfigured{false};

int toAndroidLogPriority(DWORD level) {
    switch (level) {
        case WLOG_FATAL:
            return ANDROID_LOG_FATAL;
        case WLOG_ERROR:
            return ANDROID_LOG_ERROR;
        case WLOG_WARN:
            return ANDROID_LOG_WARN;
        case WLOG_INFO:
            return ANDROID_LOG_INFO;
        case WLOG_DEBUG:
            return ANDROID_LOG_DEBUG;
        case WLOG_TRACE:
            return ANDROID_LOG_VERBOSE;
        default:
            return ANDROID_LOG_INFO;
    }
}

BOOL winprMessageCallback(const wLogMessage* msg) {
    if (msg == nullptr) {
        return TRUE;
    }
    const char* prefix = msg->PrefixString != nullptr ? msg->PrefixString : "wlog";
    const char* text =
        msg->TextString != nullptr ? msg->TextString
                                   : (msg->FormatString != nullptr ? msg->FormatString : "(empty)");
    __android_log_print(toAndroidLogPriority(msg->Level), kLogTag, "[FreeRDP][%s] %s", prefix, text);
    return TRUE;
}

BOOL winprDataCallback(const wLogMessage* msg) {
    return winprMessageCallback(msg);
}

BOOL winprImageCallback(const wLogMessage* msg) {
    return winprMessageCallback(msg);
}

BOOL winprPacketCallback(const wLogMessage* msg) {
    return winprMessageCallback(msg);
}

void configureWinprLogging() {
    bool expected = false;
    if (!g_winprLogConfigured.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
        return;
    }

    wLog* root = WLog_GetRoot();
    if (root == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "WLog_GetRoot returned null");
        return;
    }

    if (!WLog_SetLogAppenderType(root, WLOG_APPENDER_CALLBACK)) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "WLog_SetLogAppenderType callback failed");
        return;
    }

    wLogAppender* appender = WLog_GetLogAppender(root);
    if (appender == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "WLog_GetLogAppender returned null");
        return;
    }

    wLogCallbacks callbacks = {};
    callbacks.message = winprMessageCallback;
    callbacks.data = winprDataCallback;
    callbacks.image = winprImageCallback;
    callbacks.package = winprPacketCallback;

    if (!WLog_ConfigureAppender(appender, "callbacks", &callbacks)) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "WLog_ConfigureAppender callbacks failed");
        return;
    }

    wLogLayout* layout = WLog_GetLogLayout(root);
    if (layout != nullptr) {
        (void)WLog_Layout_SetPrefixFormat(root, layout, "%mn");
    }

    (void)WLog_SetLogLevel(root, WLOG_TRACE);
    (void)WLog_OpenAppender(root);
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "WinPR/FreeRDP logging bridge enabled");
}

bool ensureDirectoryRecursive(const std::string& path) {
    if (path.empty()) {
        return false;
    }

    size_t pos = 0;
    while (true) {
        pos = path.find('/', pos + 1);
        const std::string current = (pos == std::string::npos) ? path : path.substr(0, pos);
        if (current.empty()) {
            if (pos == std::string::npos) {
                break;
            }
            continue;
        }

        struct stat st {};
        if (stat(current.c_str(), &st) == 0) {
            if ((st.st_mode & S_IFDIR) == 0) {
                __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Path exists but not directory: %s", current.c_str());
                return false;
            }
        } else {
            if (mkdir(current.c_str(), 0700) != 0 && errno != EEXIST) {
                __android_log_print(ANDROID_LOG_ERROR,
                                    kLogTag,
                                    "mkdir failed: %s errno=%d",
                                    current.c_str(),
                                    errno);
                return false;
            }
        }

        if (pos == std::string::npos) {
            break;
        }
    }

    return true;
}

bool setEnvPath(const char* key, const std::string& value) {
    if (key == nullptr || value.empty()) {
        return false;
    }

    if (setenv(key, value.c_str(), 1) != 0) {
        __android_log_print(ANDROID_LOG_WARN,
                            kLogTag,
                            "setenv failed: %s=%s errno=%d",
                            key,
                            value.c_str(),
                            errno);
        return false;
    }

    __android_log_print(ANDROID_LOG_DEBUG, kLogTag, "setenv %s=%s", key, value.c_str());
    return true;
}

std::string jstringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return "";
    }

    const char* raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) {
        return "";
    }

    std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    return result;
}

std::string toSafeString(const char* value) {
    return value != nullptr ? std::string(value) : std::string();
}

std::string jsonEscape(const std::string& input) {
    std::ostringstream out;
    for (const unsigned char ch : input) {
        switch (ch) {
            case '\\':
                out << "\\\\";
                break;
            case '"':
                out << "\\\"";
                break;
            case '\b':
                out << "\\b";
                break;
            case '\f':
                out << "\\f";
                break;
            case '\n':
                out << "\\n";
                break;
            case '\r':
                out << "\\r";
                break;
            case '\t':
                out << "\\t";
                break;
            default:
                if (ch < 0x20) {
                    out << "\\u"
                        << std::hex << std::uppercase
                        << static_cast<int>(ch);
                } else {
                    out << static_cast<char>(ch);
                }
                break;
        }
    }
    return out.str();
}

std::string buildCertificatePromptEventJson(int64_t requestId,
                                            bool changed,
                                            const std::string& host,
                                            UINT16 port,
                                            const std::string& commonName,
                                            const std::string& subject,
                                            const std::string& issuer,
                                            const std::string& fingerprint,
                                            DWORD flags) {
    std::ostringstream out;
    out << "{"
        << "\"type\":\"CERTIFICATE_PROMPT\","
        << "\"requestId\":" << requestId << ","
        << "\"changed\":" << (changed ? "true" : "false") << ","
        << "\"host\":\"" << jsonEscape(host) << "\","
        << "\"port\":" << port << ","
        << "\"commonName\":\"" << jsonEscape(commonName) << "\","
        << "\"subject\":\"" << jsonEscape(subject) << "\","
        << "\"issuer\":\"" << jsonEscape(issuer) << "\","
        << "\"fingerprint\":\"" << jsonEscape(fingerprint) << "\","
        << "\"flags\":" << static_cast<unsigned long long>(flags)
        << "}";
    return out.str();
}

std::string buildDisconnectedEventJson(jint code,
                                       UINT32 freerdpError,
                                       const std::string& message) {
    std::ostringstream out;
    out << "{"
        << "\"type\":\"SESSION_DISCONNECTED\","
        << "\"code\":" << code << ","
        << "\"freerdpError\":" << static_cast<unsigned long long>(freerdpError) << ","
        << "\"message\":\"" << jsonEscape(message) << "\""
        << "}";
    return out.str();
}

jint mapFreeRdpError(UINT32 lastError) {
    switch (lastError) {
        case FREERDP_ERROR_SUCCESS:
            return RC_OK;

        case FREERDP_ERROR_AUTHENTICATION_FAILED:
        case FREERDP_ERROR_CONNECT_LOGON_FAILURE:
        case FREERDP_ERROR_CONNECT_WRONG_PASSWORD:
        case FREERDP_ERROR_CONNECT_ACCESS_DENIED:
        case FREERDP_ERROR_CONNECT_ACCOUNT_LOCKED_OUT:
        case FREERDP_ERROR_CONNECT_NO_OR_MISSING_CREDENTIALS:
            return RC_AUTH_FAILURE;

        case FREERDP_ERROR_TLS_CONNECT_FAILED:
        case FREERDP_ERROR_SECURITY_NEGO_CONNECT_FAILED:
            return RC_TLS_FAILURE;

        case FREERDP_ERROR_RPC_INITIATED_DISCONNECT:
        case FREERDP_ERROR_RPC_INITIATED_LOGOFF:
        case FREERDP_ERROR_LOGOFF_BY_USER:
        case FREERDP_ERROR_DISCONNECTED_BY_OTHER_CONNECTION:
            return RC_NOT_CONNECTED;

        case FREERDP_ERROR_DNS_ERROR:
        case FREERDP_ERROR_DNS_NAME_NOT_FOUND:
        case FREERDP_ERROR_CONNECT_FAILED:
        case FREERDP_ERROR_CONNECT_TRANSPORT_FAILED:
        case FREERDP_ERROR_CONNECT_CANCELLED:
        case FREERDP_ERROR_MCS_CONNECT_INITIAL_ERROR:
            return RC_NETWORK_FAILURE;

        default:
            return RC_INTERNAL_ERROR;
    }
}

RdpSession* lookupSession(freerdp* instance) {
    std::lock_guard<std::mutex> lock(g_instanceMapMutex);
    const auto it = g_instanceMap.find(instance);
    if (it == g_instanceMap.end()) {
        return nullptr;
    }
    return it->second;
}

void rememberSession(freerdp* instance, RdpSession* session) {
    std::lock_guard<std::mutex> lock(g_instanceMapMutex);
    g_instanceMap[instance] = session;
    __android_log_print(ANDROID_LOG_DEBUG,
                        kLogTag,
                        "rememberSession: instance=%p session=%p mapSize=%zu",
                        static_cast<void*>(instance),
                        static_cast<void*>(session),
                        g_instanceMap.size());
}

void forgetSession(freerdp* instance) {
    std::lock_guard<std::mutex> lock(g_instanceMapMutex);
    g_instanceMap.erase(instance);
    __android_log_print(ANDROID_LOG_DEBUG,
                        kLogTag,
                        "forgetSession: instance=%p mapSize=%zu",
                        static_cast<void*>(instance),
                        g_instanceMap.size());
}

struct RdpSession {
    std::string host;
    int port;
    std::string username;
    std::string password;
    std::string domain;

    std::mutex connectMutex;
    std::condition_variable connectCv;
    bool connectFinished = false;
    jint connectResult = RC_INTERNAL_ERROR;

    std::atomic<bool> stopRequested{false};
    std::atomic<bool> connected{false};

    std::mutex frameMutex;
    std::vector<uint8_t> frameBuffer;
    int frameWidth = 0;
    int frameHeight = 0;
    int frameStride = 0;
    uint32_t frameSequence = 0;

    std::mutex ioMutex;

    std::mutex eventMutex;
    std::deque<std::string> pendingEvents;

    std::mutex certificateMutex;
    std::condition_variable certificateCv;
    int64_t pendingCertificateRequestId = 0;
    bool waitingCertificateDecision = false;
    bool certificateAccepted = false;
    bool certificateDecisionReady = false;
    bool certificateRejectedByUser = false;

    freerdp* instance = nullptr;
    HANDLE stopEvent = nullptr;
    std::thread worker;

    RdpSession(std::string hostValue,
               int portValue,
               std::string usernameValue,
               std::string passwordValue,
               std::string domainValue)
        : host(std::move(hostValue)),
          port(portValue),
          username(std::move(usernameValue)),
          password(std::move(passwordValue)),
          domain(std::move(domainValue)) {}

    ~RdpSession() {
        stop();
    }

    bool start() {
        stopEvent = CreateEventA(nullptr, TRUE, FALSE, nullptr);
        if (stopEvent == nullptr) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "RdpSession::start failed: CreateEventA returned null");
            return false;
        }

        try {
            worker = std::thread([this]() { run(); });
            return true;
        } catch (const std::exception& ex) {
            __android_log_print(ANDROID_LOG_ERROR,
                                kLogTag,
                                "RdpSession::start failed: thread creation exception: %s",
                                ex.what());
            CloseHandle(stopEvent);
            stopEvent = nullptr;
            return false;
        } catch (...) {
            __android_log_print(ANDROID_LOG_ERROR,
                                kLogTag,
                                "RdpSession::start failed: thread creation unknown exception");
            CloseHandle(stopEvent);
            stopEvent = nullptr;
            return false;
        }
    }

    jint waitForConnectResult(int timeoutMs) {
        std::unique_lock<std::mutex> lock(connectMutex);
        if (!connectCv.wait_for(lock,
                                std::chrono::milliseconds(timeoutMs),
                                [this]() { return connectFinished; })) {
            return RC_NETWORK_FAILURE;
        }

        return connectResult;
    }

    void stop() {
        stopRequested.store(true);

        if (stopEvent != nullptr) {
            SetEvent(stopEvent);
        }

        {
            std::lock_guard<std::mutex> lock(certificateMutex);
            if (waitingCertificateDecision) {
                certificateDecisionReady = true;
                certificateAccepted = false;
            }
        }
        certificateCv.notify_all();

        {
            std::lock_guard<std::mutex> lock(ioMutex);
            if (instance != nullptr && instance->context != nullptr) {
                freerdp_abort_connect_context(instance->context);
            }
        }

        if (worker.joinable()) {
            worker.join();
        }

        if (stopEvent != nullptr) {
            CloseHandle(stopEvent);
            stopEvent = nullptr;
        }
    }

    bool isTarget(const std::string& hostValue, int portValue) const {
        const bool stillConnecting = !connectFinished;
        return (connected.load() || stillConnecting) && host == hostValue && port == portValue;
    }

    void setConnectResult(jint code) {
        {
            std::lock_guard<std::mutex> lock(connectMutex);
            connectFinished = true;
            connectResult = code;
        }
        connectCv.notify_all();
    }

    void pushEvent(const std::string& eventJson) {
        std::lock_guard<std::mutex> lock(eventMutex);
        pendingEvents.push_back(eventJson);
    }

    bool popEvent(std::string* outEvent) {
        if (outEvent == nullptr) {
            return false;
        }

        std::lock_guard<std::mutex> lock(eventMutex);
        if (pendingEvents.empty()) {
            return false;
        }

        *outEvent = std::move(pendingEvents.front());
        pendingEvents.pop_front();
        return true;
    }

    bool requestCertificateDecision(bool changed,
                                    const char* hostValue,
                                    UINT16 portValue,
                                    const char* commonName,
                                    const char* subject,
                                    const char* issuer,
                                    const char* fingerprint,
                                    DWORD flags) {
        const int64_t requestId = g_certificateRequestCounter.fetch_add(1);

        {
            std::lock_guard<std::mutex> lock(certificateMutex);
            pendingCertificateRequestId = requestId;
            waitingCertificateDecision = true;
            certificateDecisionReady = false;
            certificateAccepted = false;
            certificateRejectedByUser = false;
        }

        pushEvent(buildCertificatePromptEventJson(requestId,
                                                  changed,
                                                  toSafeString(hostValue),
                                                  portValue,
                                                  toSafeString(commonName),
                                                  toSafeString(subject),
                                                  toSafeString(issuer),
                                                  toSafeString(fingerprint),
                                                  flags));

        std::unique_lock<std::mutex> lock(certificateMutex);
        const bool ready = certificateCv.wait_for(
            lock,
            std::chrono::seconds(90),
            [this]() { return certificateDecisionReady || stopRequested.load(); });

        const bool accepted = ready && certificateDecisionReady && certificateAccepted && !stopRequested.load();
        if (!accepted) {
            certificateRejectedByUser = true;
        }

        pendingCertificateRequestId = 0;
        waitingCertificateDecision = false;
        certificateDecisionReady = false;
        certificateAccepted = false;
        return accepted;
    }

    jint submitCertificateDecision(int64_t requestId, bool accept) {
        {
            std::lock_guard<std::mutex> lock(certificateMutex);
            if (!waitingCertificateDecision || pendingCertificateRequestId != requestId) {
                return RC_INVALID_ARGUMENT;
            }
            certificateAccepted = accept;
            certificateDecisionReady = true;
            if (!accept) {
                certificateRejectedByUser = true;
            }
        }

        certificateCv.notify_all();
        return RC_OK;
    }

    bool wasCertificateRejectedByUser() {
        std::lock_guard<std::mutex> lock(certificateMutex);
        return certificateRejectedByUser;
    }

    void applyDesktopSizeFromSettings(const rdpSettings* settings) {
        if (settings == nullptr) {
            return;
        }

        const int width = static_cast<int>(
            freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth));
        const int height = static_cast<int>(
            freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight));

        if (width <= 0 || height <= 0) {
            return;
        }

        std::lock_guard<std::mutex> lock(frameMutex);
        frameWidth = width;
        frameHeight = height;
        frameStride = width * 4;
        frameBuffer.assign(static_cast<size_t>(frameStride) * static_cast<size_t>(frameHeight), 0);
        frameSequence++;
    }

    bool captureFrame(rdpContext* context) {
        if (context == nullptr || context->gdi == nullptr || context->gdi->primary_buffer == nullptr) {
            return false;
        }

        rdpGdi* gdi = context->gdi;
        const int width = gdi->width;
        const int height = gdi->height;
        const int stride = gdi->stride;

        if (width <= 0 || height <= 0 || stride <= 0) {
            return false;
        }

        const size_t dataSize = static_cast<size_t>(stride) * static_cast<size_t>(height);

        std::lock_guard<std::mutex> lock(frameMutex);
        frameWidth = width;
        frameHeight = height;
        frameStride = stride;
        frameBuffer.resize(dataSize);
        std::memcpy(frameBuffer.data(), gdi->primary_buffer, dataSize);
        frameSequence++;
        return true;
    }

    jint getFrameInfo(jint* outInfo, jsize length) {
        if (outInfo == nullptr || length < 3) {
            return RC_INVALID_ARGUMENT;
        }

        if (!connected.load()) {
            return RC_NOT_CONNECTED;
        }

        std::lock_guard<std::mutex> lock(frameMutex);
        if (frameWidth <= 0 || frameHeight <= 0 || frameBuffer.empty()) {
            return RC_FRAME_UNAVAILABLE;
        }

        outInfo[0] = frameWidth;
        outInfo[1] = frameHeight;
        outInfo[2] = static_cast<jint>(frameSequence);
        return RC_OK;
    }

    jint copyFrameToBitmap(JNIEnv* env, jobject bitmap) {
        if (env == nullptr || bitmap == nullptr) {
            return RC_INVALID_ARGUMENT;
        }

        if (!connected.load()) {
            return RC_NOT_CONNECTED;
        }

        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
            return RC_INTERNAL_ERROR;
        }

        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            return RC_INVALID_ARGUMENT;
        }

        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || pixels == nullptr) {
            return RC_INTERNAL_ERROR;
        }

        jint result = RC_OK;
        {
            std::lock_guard<std::mutex> lock(frameMutex);
            if (frameWidth <= 0 || frameHeight <= 0 || frameBuffer.empty()) {
                result = RC_FRAME_UNAVAILABLE;
            } else {
                const uint32_t copyWidth =
                    std::min<uint32_t>(info.width, static_cast<uint32_t>(frameWidth));
                const uint32_t copyHeight =
                    std::min<uint32_t>(info.height, static_cast<uint32_t>(frameHeight));
                const size_t dstStride = info.stride;
                const size_t srcStride = static_cast<size_t>(frameStride);
                const size_t rowBytes = copyWidth * 4;

                auto* dst = static_cast<uint8_t*>(pixels);
                const auto* src = frameBuffer.data();

                for (uint32_t row = 0; row < copyHeight; row++) {
                    std::memcpy(dst + (static_cast<size_t>(row) * dstStride),
                                src + (static_cast<size_t>(row) * srcStride),
                                rowBytes);
                }
            }
        }

        if (AndroidBitmap_unlockPixels(env, bitmap) < 0) {
            return RC_INTERNAL_ERROR;
        }

        return result;
    }

    jint sendText(JNIEnv* env, jstring text) {
        if (env == nullptr || text == nullptr) {
            return RC_INVALID_ARGUMENT;
        }

        if (!connected.load()) {
            return RC_NOT_CONNECTED;
        }

        const jsize length = env->GetStringLength(text);
        if (length <= 0) {
            return RC_OK;
        }

        const jchar* chars = env->GetStringChars(text, nullptr);
        if (chars == nullptr) {
            return RC_INTERNAL_ERROR;
        }

        jint result = RC_OK;
        {
            std::lock_guard<std::mutex> lock(ioMutex);
            if (instance == nullptr || instance->context == nullptr || instance->context->input == nullptr) {
                result = RC_NOT_CONNECTED;
            } else {
                for (jsize i = 0; i < length; i++) {
                    const UINT16 code = static_cast<UINT16>(chars[i]);
                    if (!freerdp_input_send_unicode_keyboard_event(instance->context->input, 0, code) ||
                        !freerdp_input_send_unicode_keyboard_event(
                            instance->context->input, KBD_FLAGS_RELEASE, code)) {
                        result = RC_INPUT_FAILURE;
                        break;
                    }
                }
            }
        }

        env->ReleaseStringChars(text, chars);
        return result;
    }

    jint sendPointerEvent(jint action, jint x, jint y, jint delta) {
        if (!connected.load()) {
            return RC_NOT_CONNECTED;
        }

        std::lock_guard<std::mutex> lock(ioMutex);
        if (instance == nullptr || instance->context == nullptr || instance->context->input == nullptr) {
            return RC_NOT_CONNECTED;
        }

        const UINT16 cursorX = static_cast<UINT16>(std::max(0, x));
        const UINT16 cursorY = static_cast<UINT16>(std::max(0, y));

        auto* input = instance->context->input;
        switch (action) {
            case POINTER_ACTION_MOVE:
                return freerdp_input_send_mouse_event(input, PTR_FLAGS_MOVE, cursorX, cursorY)
                           ? RC_OK
                           : RC_INPUT_FAILURE;

            case POINTER_ACTION_LEFT_CLICK:
                if (!freerdp_input_send_mouse_event(
                        input, static_cast<UINT16>(PTR_FLAGS_BUTTON1 | PTR_FLAGS_DOWN), cursorX, cursorY) ||
                    !freerdp_input_send_mouse_event(input, PTR_FLAGS_BUTTON1, cursorX, cursorY)) {
                    return RC_INPUT_FAILURE;
                }
                return RC_OK;

            case POINTER_ACTION_RIGHT_CLICK:
                if (!freerdp_input_send_mouse_event(
                        input, static_cast<UINT16>(PTR_FLAGS_BUTTON2 | PTR_FLAGS_DOWN), cursorX, cursorY) ||
                    !freerdp_input_send_mouse_event(input, PTR_FLAGS_BUTTON2, cursorX, cursorY)) {
                    return RC_INPUT_FAILURE;
                }
                return RC_OK;

            case POINTER_ACTION_SCROLL: {
                const UINT16 rotation = static_cast<UINT16>(std::min(255, std::abs(delta)));
                UINT16 flags = static_cast<UINT16>(PTR_FLAGS_WHEEL | (rotation & WheelRotationMask));
                if (delta < 0) {
                    flags = static_cast<UINT16>(flags | PTR_FLAGS_WHEEL_NEGATIVE);
                }
                return freerdp_input_send_mouse_event(input, flags, cursorX, cursorY) ? RC_OK
                                                                                        : RC_INPUT_FAILURE;
            }

            default:
                return RC_INVALID_ARGUMENT;
        }
    }

    bool applyConnectionSettings(bool rdpOnlyMode) {
        if (instance == nullptr || instance->context == nullptr || instance->context->settings == nullptr) {
            return false;
        }

        rdpSettings* settings = instance->context->settings;

        const BOOL tlsSecurity = rdpOnlyMode ? FALSE : TRUE;
        const BOOL nlaSecurity = rdpOnlyMode ? FALSE : TRUE;
        const BOOL negotiateSecurity = rdpOnlyMode ? FALSE : TRUE;
        const BOOL useRdpSecurityLayer = rdpOnlyMode ? TRUE : FALSE;

        const bool ok =
            freerdp_settings_set_string(settings, FreeRDP_ServerHostname, host.c_str()) &&
            freerdp_settings_set_uint32(settings, FreeRDP_ServerPort, static_cast<UINT32>(port)) &&
            freerdp_settings_set_string(settings, FreeRDP_Username, username.c_str()) &&
            freerdp_settings_set_string(settings, FreeRDP_Password, password.c_str()) &&
            freerdp_settings_set_string(settings, FreeRDP_Domain, domain.c_str()) &&
            freerdp_settings_set_uint32(settings, FreeRDP_DesktopWidth, kDefaultDesktopWidth) &&
            freerdp_settings_set_uint32(settings, FreeRDP_DesktopHeight, kDefaultDesktopHeight) &&
            freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth, kDefaultColorDepth) &&
            freerdp_settings_set_uint32(settings, FreeRDP_ConnectionType, CONNECTION_TYPE_INVALID) &&
            freerdp_settings_set_bool(settings, FreeRDP_NetworkAutoDetect, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_SupportHeartbeatPdu, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_SupportMultitransport, FALSE) &&
            freerdp_settings_set_uint32(settings, FreeRDP_MultitransportFlags, 0) &&
            freerdp_settings_set_bool(settings, FreeRDP_TlsSecurity, tlsSecurity) &&
            freerdp_settings_set_bool(settings, FreeRDP_NlaSecurity, nlaSecurity) &&
            freerdp_settings_set_bool(settings, FreeRDP_RdpSecurity, TRUE) &&
            freerdp_settings_set_bool(settings, FreeRDP_NegotiateSecurityLayer, negotiateSecurity) &&
            freerdp_settings_set_bool(settings, FreeRDP_UseRdpSecurityLayer, useRdpSecurityLayer) &&
            freerdp_settings_set_uint32(settings,
                                        FreeRDP_EncryptionMethods,
                                        ENCRYPTION_METHOD_40BIT | ENCRYPTION_METHOD_56BIT |
                                            ENCRYPTION_METHOD_128BIT) &&
            freerdp_settings_set_uint32(settings, FreeRDP_ExtEncryptionMethods, 0) &&
            freerdp_settings_set_bool(settings, FreeRDP_SupportSkipChannelJoin, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_IgnoreCertificate, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_DeviceRedirection, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_RedirectDrives, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_RedirectHomeDrive, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_RedirectSmartCards, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_RedirectWebAuthN, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_RedirectPrinters, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_RedirectSerialPorts, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_RedirectParallelPorts, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_RedirectClipboard, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_AudioPlayback, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_AudioCapture, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_SupportDynamicChannels, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_SynchronousDynamicChannels, FALSE) &&
            freerdp_settings_set_bool(settings, FreeRDP_SoftwareGdi, TRUE) &&
            freerdp_settings_set_uint32(settings, FreeRDP_AutoReconnectMaxRetries, 3);

        __android_log_print(ANDROID_LOG_INFO,
                            kLogTag,
                            "applyConnectionSettings: %s (%s)",
                            ok ? "ok" : "failed",
                            rdpOnlyMode ? "RDP-only security mode" : "negotiated security mode: TLS/NLA/RDP");
        return ok;
    }

    bool shouldRetryWithRdpOnly(UINT32 lastError) const {
        return lastError == FREERDP_ERROR_CONNECT_TRANSPORT_FAILED ||
               lastError == FREERDP_ERROR_SECURITY_NEGO_CONNECT_FAILED ||
               lastError == FREERDP_ERROR_TLS_CONNECT_FAILED;
    }

    static BOOL contextNew(freerdp* instance, rdpContext* context) {
        if (instance == nullptr || context == nullptr) {
            return FALSE;
        }

        auto* phoneContext = reinterpret_cast<PhoneRdpContext*>(context);
        phoneContext->session = lookupSession(instance);
        __android_log_print(ANDROID_LOG_DEBUG,
                            kLogTag,
                            "contextNew: instance=%p context=%p session=%p",
                            static_cast<void*>(instance),
                            static_cast<void*>(context),
                            static_cast<void*>(phoneContext->session));
        if (phoneContext->session == nullptr) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "contextNew failed: session lookup returned null");
            return FALSE;
        }
        return phoneContext->session != nullptr ? TRUE : FALSE;
    }

    static void contextFree(freerdp* /*instance*/, rdpContext* context) {
        if (context == nullptr) {
            return;
        }

        auto* phoneContext = reinterpret_cast<PhoneRdpContext*>(context);
        phoneContext->session = nullptr;
    }

    static BOOL beginPaint(rdpContext* /*context*/) {
        return TRUE;
    }

    static BOOL endPaint(rdpContext* context) {
        if (context == nullptr) {
            return FALSE;
        }

        auto* phoneContext = reinterpret_cast<PhoneRdpContext*>(context);
        if (phoneContext->session == nullptr) {
            return FALSE;
        }

        return phoneContext->session->captureFrame(context) ? TRUE : FALSE;
    }

    static BOOL desktopResize(rdpContext* context) {
        if (context == nullptr) {
            return FALSE;
        }

        auto* phoneContext = reinterpret_cast<PhoneRdpContext*>(context);
        if (phoneContext->session == nullptr) {
            return FALSE;
        }

        phoneContext->session->applyDesktopSizeFromSettings(context->settings);
        return TRUE;
    }

    static BOOL preConnect(freerdp* instance) {
        if (instance == nullptr || instance->context == nullptr) {
            return FALSE;
        }

        __android_log_print(ANDROID_LOG_INFO,
                            kLogTag,
                            "preConnect: skip freerdp_client_load_addins (plugin-free minimal mode)");

        return TRUE;
    }

    static BOOL postConnect(freerdp* instance) {
        if (instance == nullptr || instance->context == nullptr || instance->context->update == nullptr) {
            return FALSE;
        }

        if (!gdi_init(instance, PIXEL_FORMAT_RGBX32)) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "gdi_init failed");
            return FALSE;
        }

        rdpUpdate* update = instance->context->update;
        update->BeginPaint = beginPaint;
        update->EndPaint = endPaint;
        update->DesktopResize = desktopResize;

        auto* phoneContext = reinterpret_cast<PhoneRdpContext*>(instance->context);
        if (phoneContext->session != nullptr) {
            phoneContext->session->applyDesktopSizeFromSettings(instance->context->settings);
        }

        return TRUE;
    }

    static void postDisconnect(freerdp* instance) {
        if (instance == nullptr) {
            return;
        }

        gdi_free(instance);
    }

    static BOOL authenticateEx(freerdp* /*instance*/,
                               char** /*username*/,
                               char** /*password*/,
                               char** /*domain*/,
                               rdp_auth_reason /*reason*/) {
        return TRUE;
    }

    static DWORD verifyCertificateEx(freerdp* instance,
                                     const char* host,
                                     UINT16 port,
                                     const char* commonName,
                                     const char* subject,
                                     const char* issuer,
                                     const char* fingerprint,
                                     DWORD flags) {
        auto* session = lookupSession(instance);
        if (session == nullptr) {
            return 0;
        }

        const bool accepted = session->requestCertificateDecision(
            false, host, port, commonName, subject, issuer, fingerprint, flags);
        return accepted ? 2 : 0;
    }

    static DWORD verifyChangedCertificateEx(freerdp* instance,
                                            const char* host,
                                            UINT16 port,
                                            const char* commonName,
                                            const char* subject,
                                            const char* issuer,
                                            const char* newFingerprint,
                                            const char* /*oldSubject*/,
                                            const char* /*oldIssuer*/,
                                            const char* /*oldFingerprint*/,
                                            DWORD flags) {
        auto* session = lookupSession(instance);
        if (session == nullptr) {
            return 0;
        }

        const bool accepted = session->requestCertificateDecision(
            true, host, port, commonName, subject, issuer, newFingerprint, flags);
        return accepted ? 2 : 0;
    }

    void run() {
        connectFinished = false;
        connectResult = RC_INTERNAL_ERROR;

        instance = freerdp_new();
        if (instance == nullptr) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "freerdp_new returned null");
            setConnectResult(RC_BACKEND_UNAVAILABLE);
            return;
        }

        rememberSession(instance, this);

        instance->ContextSize = sizeof(PhoneRdpContext);
        instance->ContextNew = contextNew;
        instance->ContextFree = contextFree;
        instance->PreConnect = preConnect;
        instance->PostConnect = postConnect;
        instance->PostDisconnect = postDisconnect;
        instance->AuthenticateEx = authenticateEx;
        instance->VerifyCertificateEx = verifyCertificateEx;
        instance->VerifyChangedCertificateEx = verifyChangedCertificateEx;

        if (!freerdp_context_new(instance)) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "freerdp_context_new failed");
            setConnectResult(RC_BACKEND_UNAVAILABLE);
            cleanup();
            return;
        }

        if (!applyConnectionSettings(false)) {
            setConnectResult(RC_INVALID_ARGUMENT);
            cleanup();
            return;
        }

        if (!freerdp_connect(instance)) {
            UINT32 lastError = freerdp_get_last_error(instance->context);
            __android_log_print(ANDROID_LOG_WARN,
                                kLogTag,
                                "freerdp_connect first attempt failed: 0x%08x (%s)",
                                lastError,
                                freerdp_get_last_error_string(lastError));

            if (shouldRetryWithRdpOnly(lastError)) {
                __android_log_print(ANDROID_LOG_INFO, kLogTag, "Retrying with RDP-only security mode");

                if (!applyConnectionSettings(true)) {
                    setConnectResult(RC_INVALID_ARGUMENT);
                    cleanup();
                    return;
                }

                if (!freerdp_connect(instance)) {
                    lastError = freerdp_get_last_error(instance->context);
                } else {
                    connected.store(true);
                    setConnectResult(RC_OK);
                    __android_log_print(ANDROID_LOG_INFO,
                                        kLogTag,
                                        "freerdp_connect retry succeeded (RDP-only security mode)");
                    goto connected_success;
                }
            }

            jint mapped = mapFreeRdpError(lastError);
            if (wasCertificateRejectedByUser()) {
                mapped = RC_CERTIFICATE_REJECTED;
            }

            __android_log_print(ANDROID_LOG_WARN,
                                kLogTag,
                                "freerdp_connect failed: 0x%08x (%s)",
                                lastError,
                                freerdp_get_last_error_string(lastError));
            setConnectResult(mapped);
            cleanup();
            return;
        }

        connected.store(true);
        setConnectResult(RC_OK);

connected_success:
        bool unexpectedDisconnect = false;
        jint disconnectCode = RC_OK;
        UINT32 disconnectError = FREERDP_ERROR_SUCCESS;
        std::string disconnectMessage;

        while (!stopRequested.load()) {
            HANDLE handles[kMaxWaitHandles] = {0};
            DWORD count = 0;

            handles[count++] = stopEvent;
            const DWORD freerdpHandleCount =
                freerdp_get_event_handles(instance->context, &handles[count], kMaxWaitHandles - count);

            if (freerdpHandleCount == 0) {
                unexpectedDisconnect = true;
                disconnectCode = RC_NETWORK_FAILURE;
                disconnectMessage = "FreeRDP event handle acquisition failed.";
                __android_log_print(ANDROID_LOG_ERROR, kLogTag, "freerdp_get_event_handles returned 0");
                break;
            }

            count += freerdpHandleCount;
            const DWORD status = WaitForMultipleObjects(count, handles, FALSE, 100);

            if (status == WAIT_TIMEOUT) {
                continue;
            }

            if (status == WAIT_OBJECT_0) {
                break;
            }

            if (status == WAIT_FAILED) {
                unexpectedDisconnect = true;
                disconnectCode = RC_NETWORK_FAILURE;
                disconnectMessage = "Waiting on RDP handles failed.";
                __android_log_print(ANDROID_LOG_ERROR, kLogTag, "WaitForMultipleObjects failed");
                break;
            }

            if (!freerdp_check_event_handles(instance->context)) {
                const UINT32 lastError = freerdp_get_last_error(instance->context);
                unexpectedDisconnect = true;
                disconnectError = lastError;
                disconnectCode = mapFreeRdpError(lastError);
                disconnectMessage = toSafeString(freerdp_get_last_error_string(lastError));
                __android_log_print(ANDROID_LOG_WARN,
                                    kLogTag,
                                    "freerdp_check_event_handles failed: 0x%08x (%s)",
                                    lastError,
                                    freerdp_get_last_error_string(lastError));
                break;
            }
        }

        if (instance != nullptr && instance->context != nullptr) {
            if (!freerdp_disconnect(instance) && !stopRequested.load()) {
                unexpectedDisconnect = true;
                const UINT32 lastError = freerdp_get_last_error(instance->context);
                disconnectError = lastError;
                disconnectCode = mapFreeRdpError(lastError);
                disconnectMessage = toSafeString(freerdp_get_last_error_string(lastError));
            }
        }

        if (unexpectedDisconnect && !stopRequested.load()) {
            if (disconnectError == FREERDP_ERROR_SUCCESS && instance != nullptr && instance->context != nullptr) {
                disconnectError = freerdp_get_last_error(instance->context);
            }
            if (disconnectCode == RC_OK) {
                disconnectCode = mapFreeRdpError(disconnectError);
            }
            if (disconnectCode == RC_OK) {
                disconnectCode = RC_NOT_CONNECTED;
            }
            if (disconnectMessage.empty()) {
                disconnectMessage = toSafeString(freerdp_get_last_error_string(disconnectError));
                if (disconnectMessage.empty()) {
                    disconnectMessage = "Session disconnected unexpectedly.";
                }
            }

            pushEvent(buildDisconnectedEventJson(disconnectCode, disconnectError, disconnectMessage));
        }

        cleanup();
    }

    void cleanup() {
        connected.store(false);

        {
            std::lock_guard<std::mutex> lock(frameMutex);
            frameBuffer.clear();
            frameWidth = 0;
            frameHeight = 0;
            frameStride = 0;
            frameSequence++;
        }

        if (instance != nullptr) {
            forgetSession(instance);
            freerdp_context_free(instance);
            freerdp_free(instance);
            instance = nullptr;
        }
    }
};
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_phonerdp_native_1bridge_RdpNativeBridge_nativeInit(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring homePath,
    jstring cachePath) {
    const std::string homePathValue = jstringToUtf8(env, homePath);
    const std::string cachePathValue = jstringToUtf8(env, cachePath);
    const std::string resolvedHome = homePathValue.empty() ? "/data/data/com.example.phonerdp/files" : homePathValue;
    const std::string resolvedCache =
        cachePathValue.empty() ? "/data/data/com.example.phonerdp/cache" : cachePathValue;

    const std::string xdgConfig = resolvedHome + "/.config";
    const std::string xdgData = resolvedHome + "/.local/share";
    const std::string xdgCache = resolvedCache + "/xdg-cache";
    const std::string xdgRuntime = resolvedCache + "/runtime";

    ensureDirectoryRecursive(resolvedHome);
    ensureDirectoryRecursive(resolvedCache);
    ensureDirectoryRecursive(xdgConfig);
    ensureDirectoryRecursive(xdgData);
    ensureDirectoryRecursive(xdgCache);
    ensureDirectoryRecursive(xdgRuntime);

    setEnvPath("HOME", resolvedHome);
    setEnvPath("TMPDIR", resolvedCache);
    setEnvPath("XDG_CONFIG_HOME", xdgConfig);
    setEnvPath("XDG_DATA_HOME", xdgData);
    setEnvPath("XDG_CACHE_HOME", xdgCache);
    setEnvPath("XDG_RUNTIME_DIR", xdgRuntime);

    std::lock_guard<std::mutex> lock(g_stateMutex);
    configureWinprLogging();
    g_initialized = true;
    __android_log_print(ANDROID_LOG_INFO,
                        kLogTag,
                        "nativeInit finished (home=%s cache=%s)",
                        resolvedHome.c_str(),
                        resolvedCache.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_phonerdp_native_1bridge_RdpNativeBridge_nativeConnect(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring host,
    jint port,
    jstring username,
    jstring password,
    jstring domain) {
    const std::string hostValue = jstringToUtf8(env, host);
    const std::string usernameValue = jstringToUtf8(env, username);
    const std::string passwordValue = jstringToUtf8(env, password);
    const std::string domainValue = jstringToUtf8(env, domain);

    if (hostValue.empty() || usernameValue.empty() || passwordValue.empty() || port < 1 || port > 65535) {
        return RC_INVALID_ARGUMENT;
    }

    std::unique_ptr<RdpSession> oldSession;
    {
        std::lock_guard<std::mutex> stateLock(g_stateMutex);
        if (!g_initialized) {
            return RC_BACKEND_UNAVAILABLE;
        }

        if (g_session && g_session->isTarget(hostValue, port)) {
            return RC_ALREADY_CONNECTED;
        }

        if (g_session) {
            oldSession = std::move(g_session);
        }
    }

    if (oldSession) {
        oldSession->stop();
    }

    auto newSession = std::make_unique<RdpSession>(
        hostValue, static_cast<int>(port), usernameValue, passwordValue, domainValue);

    if (!newSession->start()) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "nativeConnect failed: session start failed");
        return RC_BACKEND_UNAVAILABLE;
    }

    RdpSession* pendingSession = newSession.get();
    {
        std::lock_guard<std::mutex> stateLock(g_stateMutex);
        g_session = std::move(newSession);
    }

    const jint connectResult = pendingSession->waitForConnectResult(120000);
    if (connectResult < 0) {
        std::unique_ptr<RdpSession> failedSession;
        {
            std::lock_guard<std::mutex> stateLock(g_stateMutex);
            if (g_session.get() == pendingSession) {
                failedSession = std::move(g_session);
            }
        }

        if (failedSession) {
            failedSession->stop();
        }
        return connectResult;
    }

    __android_log_print(ANDROID_LOG_INFO, kLogTag, "nativeConnect success -> %s:%d", hostValue.c_str(), port);
    return connectResult;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_phonerdp_native_1bridge_RdpNativeBridge_nativeDisconnect(
    JNIEnv* /*env*/,
    jobject /*thiz*/) {
    std::unique_ptr<RdpSession> session;
    {
        std::lock_guard<std::mutex> lock(g_stateMutex);
        if (!g_initialized) {
            return RC_BACKEND_UNAVAILABLE;
        }
        if (!g_session) {
            return RC_NOT_CONNECTED;
        }
        session = std::move(g_session);
    }

    session->stop();
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "nativeDisconnect success");
    return RC_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_phonerdp_native_1bridge_RdpNativeBridge_nativeSendTextInput(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring text) {
    std::lock_guard<std::mutex> lock(g_stateMutex);

    if (!g_initialized) {
        return RC_BACKEND_UNAVAILABLE;
    }
    if (!g_session) {
        return RC_NOT_CONNECTED;
    }

    return g_session->sendText(env, text);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_phonerdp_native_1bridge_RdpNativeBridge_nativeSendPointerEvent(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint action,
    jint x,
    jint y,
    jint delta) {
    std::lock_guard<std::mutex> lock(g_stateMutex);

    if (!g_initialized) {
        return RC_BACKEND_UNAVAILABLE;
    }
    if (!g_session) {
        return RC_NOT_CONNECTED;
    }

    return g_session->sendPointerEvent(action, x, y, delta);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_phonerdp_native_1bridge_RdpNativeBridge_nativeGetFrameInfo(
    JNIEnv* env,
    jobject /*thiz*/,
    jintArray outInfo) {
    if (outInfo == nullptr) {
        return RC_INVALID_ARGUMENT;
    }

    std::lock_guard<std::mutex> lock(g_stateMutex);
    if (!g_initialized) {
        return RC_BACKEND_UNAVAILABLE;
    }
    if (!g_session) {
        return RC_NOT_CONNECTED;
    }

    const jsize length = env->GetArrayLength(outInfo);
    if (length < 3) {
        return RC_INVALID_ARGUMENT;
    }

    jint buffer[3] = {0, 0, 0};
    const jint rc = g_session->getFrameInfo(buffer, length);
    if (rc >= 0) {
        env->SetIntArrayRegion(outInfo, 0, 3, buffer);
    }

    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_phonerdp_native_1bridge_RdpNativeBridge_nativeCopyFrameToBitmap(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject bitmap) {
    std::lock_guard<std::mutex> lock(g_stateMutex);

    if (!g_initialized) {
        return RC_BACKEND_UNAVAILABLE;
    }
    if (!g_session) {
        return RC_NOT_CONNECTED;
    }

    return g_session->copyFrameToBitmap(env, bitmap);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_phonerdp_native_1bridge_RdpNativeBridge_nativePollEvent(
    JNIEnv* env,
    jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_stateMutex);

    if (!g_initialized || !g_session) {
        return nullptr;
    }

    std::string event;
    if (!g_session->popEvent(&event)) {
        return nullptr;
    }

    return env->NewStringUTF(event.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_phonerdp_native_1bridge_RdpNativeBridge_nativeSubmitCertificateDecision(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong requestId,
    jboolean accept) {
    std::lock_guard<std::mutex> lock(g_stateMutex);

    if (!g_initialized) {
        return RC_BACKEND_UNAVAILABLE;
    }
    if (!g_session) {
        return RC_NOT_CONNECTED;
    }

    return g_session->submitCertificateDecision(static_cast<int64_t>(requestId), accept == JNI_TRUE);
}
