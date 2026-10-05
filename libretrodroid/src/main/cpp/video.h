/*
 *     Copyright (C) 2019  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#ifndef LIBRETRODROID_VIDEO_H
#define LIBRETRODROID_VIDEO_H

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <optional>
#include <array>

#include "renderers/renderer.h"
#include "shadermanager.h"
#include "utils/rect.h"
#include "immersivemode.h"
#include "videolayout.h"

namespace libretrodroid {

class Video {
public:

    struct RenderingOptions {
        bool hardwareAccelerated = false;
        unsigned int width;
        unsigned int height;
        bool useDepth;
        bool useStencil;
        int openglESVersion;
        int pixelFormat;
        /** O núcleo desenha com Vulkan: os quadros chegam pelo VulkanContext. */
        bool vulkan = false;
    };

    struct ShaderChainEntry {
        GLint gProgram = 0;
        GLint gvPositionHandle = 0;
        GLint gvCoordinateHandle = 0;
        GLint gTextureHandle = 0;
        GLint gPreviousPassTextureHandle = 0;
        GLint gScreenDensityHandle = 0;
        GLint gTextureSizeHandle = 0;
    };

    Video(
        RenderingOptions renderingOptions,
        ShaderManager::Config shaderConfig,
        bool bottomLeftOrigin,
        float rotation,
        bool skipDuplicateFrames,
        bool immersiveMode,
        Rect viewportRect,
        ImmersiveMode::Config immersiveModeConfig
    );
    ~Video();

    Video(const Video&) = delete;
    Video& operator=(const Video&) = delete;

    VideoLayout& getLayout() { return videoLayout; }

    void updateAspectRatio(float aspectRatio);
    void updateScreenSize(unsigned screenWidth, unsigned screenHeight);
    void updateViewportSize(Rect viewportRect);
    void updateRendererSize(unsigned width, unsigned height);
    void updateRotation(float rotation);
    void updateShaderType(ShaderManager::Config shaderConfig);

    void renderFrame();

    /**
     * Redesenha o último quadro sem tratá-lo como novo (não mexe em isDirty nem em frameRendered, então a captura
     * não recebe duplicata). Para os vsyncs entre dois quadros do núcleo, quando a tela é um múltiplo do conteúdo.
     */
    void representFrame();

    /** Houve quadro novo na janela desde a última chamada (o repetido não é redesenhado). */
    bool takeFrameRendered();

    /**
     * Copia a área do jogo da janela (superfície atual) para uma textura própria. Depois, com a superfície
     * do encoder atual, [drawCapture] desenha essa cópia nela. Duas cópias comuns em vez de ler de uma
     * superfície e escrever em outra: no ANGLE essa leitura cruzada saía na escala da superfície errada.
     * Exigem GLES 3 (glBlitFramebuffer).
     */
    bool copyForeground();
    void drawCapture(int targetWidth, int targetHeight);

private:
    void drawFrame();

public:

    void onNewFrame(const void *data, unsigned width, unsigned height, size_t pitch);

    uintptr_t getCurrentFramebuffer() {
        return renderer->getFramebuffer();
    };

    bool rendersInVideoCallback() {
        return renderer->rendersInVideoCallback();
    }

private:
    void updateProgram();

    float getScreenDensity();
    float getTextureWidth();
    float getTextureHeight();

    void initializeRenderer(RenderingOptions renderingOptions);
    void deletePrograms();
    /** O contexto em que os objetos GL desta Video foram criados é o atual (só nele dá para apagá-los). */
    bool ownsCurrentContext() const;

private:
    ShaderManager::Config requestedShaderConfig = ShaderManager::Config {
        ShaderManager::Type::SHADER_DEFAULT
    };
    std::optional<ShaderManager::Config> loadedShaderType = std::nullopt;

    bool isDirty = false;
    bool frameRendered = false;
    // O núcleo já entregou algum quadro: antes disso não há textura para reapresentar.
    bool hasFrame = false;

    GLuint captureFramebuffer = 0;
    GLuint captureTexture = 0;
    int captureWidth = 0;
    int captureHeight = 0;
    bool skipDuplicateFrames = false;
    bool hardwareAccelerated = false;

    std::vector<ShaderChainEntry> shadersChain;

    bool immersiveModeEnabled = false;
    ImmersiveMode immersiveMode;
    VideoLayout videoLayout;

    Renderer* renderer = nullptr;
    // Contexto EGL da criação: os nomes GL só valem nele (um contexto novo pode reusar os mesmos números).
    EGLContext glContext = EGL_NO_CONTEXT;
};

}

#endif //LIBRETRODROID_VIDEO_H
