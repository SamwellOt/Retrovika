#include "capture.h"

#include <EGL/eglext.h>
#include <time.h>

#include "log.h"
#include "video.h"

namespace libretrodroid {

namespace {

// Um segundo de áudio estéreo a 48 kHz: o Java lê bem antes disso encher.
constexpr size_t AUDIO_RING_SAMPLES = 48000 * 2;

typedef EGLBoolean (*PresentationTimeFn)(EGLDisplay, EGLSurface, EGLnsecsANDROID);

int64_t monotonicNanos() {
    timespec ts {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

/** A configuração do contexto atual: a superfície do encoder precisa ser compatível com ele. */
EGLConfig currentConfig(EGLDisplay display, EGLContext context) {
    EGLint configId = 0;
    eglQueryContext(display, context, EGL_CONFIG_ID, &configId);
    EGLint attributes[] = { EGL_CONFIG_ID, configId, EGL_NONE };
    EGLConfig config = nullptr;
    EGLint count = 0;
    if (!eglChooseConfig(display, attributes, &config, 1, &count) || count == 0) return nullptr;
    return config;
}

}

Capture::~Capture() {
    std::lock_guard<std::mutex> lock(windowLock);
    if (pendingWindow != nullptr) ANativeWindow_release(pendingWindow);
    pendingWindow = nullptr;
}

void Capture::setWindow(ANativeWindow* newWindow, int newWidth, int newHeight) {
    std::lock_guard<std::mutex> lock(windowLock);
    if (pendingWindow != nullptr) ANativeWindow_release(pendingWindow);
    pendingWindow = newWindow;
    pendingWidth = newWidth;
    pendingHeight = newHeight;
    windowChanged = true;
}

void Capture::applyPendingWindow() {
    ANativeWindow* next = nullptr;
    int nextWidth = 0;
    int nextHeight = 0;
    {
        std::lock_guard<std::mutex> lock(windowLock);
        if (!windowChanged) return;
        windowChanged = false;
        next = pendingWindow;
        pendingWindow = nullptr;
        nextWidth = pendingWidth;
        nextHeight = pendingHeight;
    }

    destroySurface();
    if (next == nullptr) return;

    EGLDisplay currentDisplay = eglGetCurrentDisplay();
    EGLContext context = eglGetCurrentContext();
    EGLConfig config = currentConfig(currentDisplay, context);
    EGLSurface created = config != nullptr
        ? eglCreateWindowSurface(currentDisplay, config, next, nullptr)
        : EGL_NO_SURFACE;
    if (created == EGL_NO_SURFACE) {
        LOGE("Capture: eglCreateWindowSurface failed (0x%x)", eglGetError());
        ANativeWindow_release(next);
        return;
    }
    display = currentDisplay;
    surface = created;
    window = next;
    width = nextWidth;
    height = nextHeight;
}

void Capture::destroySurface() {
    if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
    if (window != nullptr) ANativeWindow_release(window);
    surface = EGL_NO_SURFACE;
    window = nullptr;
}

void Capture::onFrameRendered(Video& video) {
    applyPendingWindow();
    if (surface == EGL_NO_SURFACE) return;

    EGLSurface windowDraw = eglGetCurrentSurface(EGL_DRAW);
    EGLSurface windowRead = eglGetCurrentSurface(EGL_READ);
    EGLContext context = eglGetCurrentContext();

    // A janela ainda tem o quadro (a troca de buffers vem depois): copia antes de mudar de superfície.
    if (!video.copyForeground()) return;
    if (!eglMakeCurrent(display, surface, surface, context)) {
        LOGE("Capture: eglMakeCurrent failed (0x%x)", eglGetError());
        destroySurface();
        eglMakeCurrent(display, windowDraw, windowRead, context);
        return;
    }
    // Sem esperar o encoder: se ele atrasar, perde-se um quadro da transmissão, não do jogo.
    eglSwapInterval(display, 0);
    video.drawCapture(width, height);

    static auto presentationTime =
        (PresentationTimeFn) eglGetProcAddress("eglPresentationTimeANDROID");
    if (presentationTime != nullptr) presentationTime(display, surface, monotonicNanos());
    bool swapped = eglSwapBuffers(display, surface);
    EGLint swapError = eglGetError();
    eglMakeCurrent(display, windowDraw, windowRead, context);
    if (!swapped) {
        // O encoder foi liberado do lado Java: para de copiar até vir outra superfície.
        LOGE("Capture: eglSwapBuffers failed (0x%x)", swapError);
        destroySurface();
    }
}

void Capture::release() {
    {
        std::lock_guard<std::mutex> lock(windowLock);
        if (pendingWindow != nullptr) ANativeWindow_release(pendingWindow);
        pendingWindow = nullptr;
        windowChanged = false;
    }
    destroySurface();
}

void Capture::setAudioEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(audioLock);
    audioEnabled = enabled;
    audioRead = 0;
    audioSize = 0;
    if (enabled && audioRing.empty()) audioRing.resize(AUDIO_RING_SAMPLES);
}

void Capture::writeAudio(const int16_t* data, size_t frames) {
    std::lock_guard<std::mutex> lock(audioLock);
    if (!audioEnabled) return;
    size_t capacity = audioRing.size();
    size_t samples = frames * 2;
    for (size_t i = 0; i < samples; i++) {
        audioRing[(audioRead + audioSize) % capacity] = data[i];
        if (audioSize < capacity) {
            audioSize++;
        } else {
            // Cheio: descarta o mais antigo, que já chegaria atrasado do outro lado.
            audioRead = (audioRead + 1) % capacity;
        }
    }
}

size_t Capture::readAudio(int16_t* out, size_t maxSamples) {
    std::lock_guard<std::mutex> lock(audioLock);
    if (!audioEnabled) return 0;
    size_t capacity = audioRing.size();
    // Sempre em pares (esquerda, direita), senão os canais trocariam de lado.
    size_t count = std::min(maxSamples, audioSize) & ~(size_t) 1;
    for (size_t i = 0; i < count; i++) {
        out[i] = audioRing[(audioRead + i) % capacity];
    }
    audioRead = (audioRead + count) % capacity;
    audioSize -= count;
    return count;
}

} // namespace libretrodroid
