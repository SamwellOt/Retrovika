#ifndef LIBRETRODROID_CAPTURE_H
#define LIBRETRODROID_CAPTURE_H

#include <EGL/egl.h>
#include <android/native_window.h>

#include <cstdint>
#include <mutex>
#include <vector>

namespace libretrodroid {

class Video;

/**
 * Cópia do jogo para fora do aparelho (transmissão para outra tela).
 *
 * Vídeo: depois de cada quadro desenhado, a área do jogo na janela é copiada para uma textura e dela para
 * a superfície de entrada de um encoder, na thread de emulação e no mesmo contexto GL. O quadro já passou
 * pelos shaders e pela rotação, então sai igual ao que aparece no celular.
 *
 * Áudio: as amostras do núcleo são copiadas para um buffer circular, lido pelo Java quando quiser.
 */
class Capture {
public:
    ~Capture();

    /** Pode ser chamado de qualquer thread; a troca da superfície acontece no próximo quadro. */
    void setWindow(ANativeWindow* window, int width, int height);

    /** Na thread de emulação, com o contexto GL atual e o quadro recém-desenhado na janela. */
    void onFrameRendered(Video& video);

    /** Libera a superfície EGL (fim do jogo). */
    void release();

    void setAudioEnabled(bool enabled);
    void writeAudio(const int16_t* data, size_t frames);
    /** Copia até [maxSamples] amostras (esquerda e direita intercaladas) e devolve quantas copiou. */
    size_t readAudio(int16_t* out, size_t maxSamples);

private:
    void applyPendingWindow();
    void destroySurface();

    std::mutex windowLock;
    bool windowChanged = false;
    ANativeWindow* pendingWindow = nullptr;
    int pendingWidth = 0;
    int pendingHeight = 0;

    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    ANativeWindow* window = nullptr;
    int width = 0;
    int height = 0;

    std::mutex audioLock;
    bool audioEnabled = false;
    std::vector<int16_t> audioRing;
    size_t audioRead = 0;
    size_t audioSize = 0;
};

} // namespace libretrodroid

#endif //LIBRETRODROID_CAPTURE_H
