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

#ifndef LIBRETRODROID_LIBRETRODROID_H
#define LIBRETRODROID_LIBRETRODROID_H

#include <jni.h>

#include <EGL/egl.h>

#include <list>
#include <string>
#include <vector>
#include <unordered_set>
#include <mutex>
#include <memory>
#include <optional>

#include "log.h"
#include "core.h"
#include "audio.h"
#include "capture.h"
#include "video.h"
#include "renderers/renderer.h"
#include "fpssync.h"
#include "input.h"
#include "rumble.h"
#include "shadermanager.h"
#include "utils/javautils.h"
#include "environment.h"
#include "vfs/vfsfile.h"
#include "renderers/es3/framebufferrenderer.h"
#include "renderers/es2/imagerendereres2.h"
#include "renderers/es3/imagerendereres3.h"
#include "utils/rect.h"
#include "netplay.h"
#include <atomic>

namespace libretrodroid {

class LibretroDroid {
public:
    static LibretroDroid& getInstance()
    {
        static LibretroDroid instance;
        return instance;
    }
    LibretroDroid(LibretroDroid const&) = delete;
    void operator=(LibretroDroid const&) = delete;

    void setViewport(Rect viewportRect);

private:
    LibretroDroid() {}

public:
    void setCheat(unsigned index, bool enabled, const std::string& code);
    void resetCheat();

    // Vazio quando o núcleo não consegue gerar o estado (o Kotlin trata como falha).
    std::vector<int8_t> serializeState();
    bool unserializeState(int8_t *data, size_t size);

    std::vector<int8_t> serializeSRAM();
    jboolean unserializeSRAM(int8_t *data, size_t size);

    void onSurfaceCreated();
    void onSurfaceChanged(unsigned int width, unsigned int height);

    void create(
        unsigned int GLESVersion,
        const std::string& soFilePath,
        const std::string& systemDir,
        const std::string& savesDir,
        std::vector<Variable> variables,
        const ShaderManager::Config& shaderConfig,
        float refreshRate,
        bool lowLatencyAudio,
        bool enableVirtualFileSystem,
        bool enableMicrophone,
        bool duplicateFrames,
        std::optional<ImmersiveMode::Config> immersiveModeConfig,
        const std::string& language
    );
    void resume();
    void step();
    void pause();
    void destroy();

    void reset();

    void loadGameFromPath(const std::string &gamePath);
    void loadGameFromBytes(std::vector<int8_t> data);
    void loadGameFromVirtualFiles(std::vector<VFSFile> virtualFiles);

    void onKeyEvent(unsigned int port, int action, int keyCode);
    void onMotionEvent(unsigned int port, unsigned int source, float xAxis, float yAxis);
    void onTouchEvent(float xAxis, float yAxis);

    void refreshAspectRatio();
    float getAspectRatio();

    // Partida em rede local (Retrovika): ver netplay.h.
    void startNetplay(int fd, unsigned localPort, unsigned delayFrames, unsigned epoch);
    void stopNetplay();
    /** -1 sem partida; -2 conexão perdida; senão, há quantos ms a entrada do outro está atrasada. */
    int64_t netplayStatus() const;
    uint32_t netplayFrame() const;

    // Teste de desempenho dos núcleos (Retrovika): quadros emulados e a taxa nativa do jogo.
    uint64_t getRunCount() const { return runCount.load(); }
    double getContentFps() const { return contentFps; }

    bool requiresVideoRefresh() const;
    void clearRequiresVideoRefresh();

    /** O núcleo desenha com Vulkan e a ponte para o GL quebrou: dali em diante só sairia tela preta. */
    bool isVideoBackendLost() const;

    std::vector<Variable> getVariables();
    void updateVariable(const Variable& variable);

    std::vector<std::vector<struct Controller>> getControllers();
    void setControllerType(unsigned int port, unsigned int type);

    int availableDisks();
    int currentDisk();
    void changeDisk(unsigned int index);

    void setRumbleEnabled(bool enabled);
    bool isRumbleEnabled() const;
    void handleRumbleUpdates(const std::function<void(int, float, float)> &handler);

    void setFrameSpeed(unsigned int speed);

    void setAudioEnabled(bool enabled);

    Capture& getCapture() { return capture; }

    /**
     * Tradução da tela: pede uma cópia do próximo quadro de software do núcleo, na resolução nativa e
     * sem shader nem escala (o OCR lê letras de pixel muito melhor assim).
     */
    void requestFrameSnapshot();
    /**
     * [largura, altura, esquerda, topo, direita, base da área do jogo na view] seguido dos pixels ARGB.
     * Vazio enquanto o quadro não chegou; largura 0 quando o núcleo desenha por GPU (não há o que copiar).
     */
    std::vector<int32_t> takeFrameSnapshot();
    /** Taxa real das amostras que o núcleo entrega (já com o ajuste ao refresh da tela). */
    int getAudioSampleRate() const { return audioSampleRate; }

    void setShaderConfig(ShaderManager::Config shaderConfig);

    void resetGlobalVariables();

    // Handle callbacks
    void handleVideoRefresh(const void *data, unsigned width, unsigned height, size_t pitch);
    size_t handleAudioCallback(const int16_t* data, size_t frames);
    int16_t handleSetInputState(unsigned port, unsigned device, unsigned index, unsigned id);
    uintptr_t handleGetCurrentFrameBuffer();

private:
    void updateAudioSampleRateMultiplier();
    void replaceVideo(std::unique_ptr<Video> newVideo);
    float findDefaultAspectRatio(const retro_system_av_info &system_av_info);
    void afterGameLoad();

protected:
    static void callback_hw_video_refresh(const void *data, unsigned width, unsigned height, size_t pitch);
    static size_t callback_set_audio_sample_batch(const int16_t* data, size_t frames);
    static void callback_audio_sample(int16_t left, int16_t right);
    static int16_t callback_set_input_state(unsigned port, unsigned device, unsigned index, unsigned id);
    static uintptr_t callback_get_current_framebuffer();

    [[noreturn]] void throwLoadGameError();
    void throwIfHwContextMissing();
    void throwIfCoreMissing();
    static void callback_retro_set_input_poll();

private:
    unsigned int frameSpeed = 1;
    bool audioEnabled = true;
    bool preferLowLatencyAudio = false;
    bool rumbleEnabled = false;

    ShaderManager::Config fragmentShaderConfig = ShaderManager::Config {
        ShaderManager::Type::SHADER_DEFAULT, { }
    };

    Rect viewportRect = Rect(0.0F, 0.0F, 1.0F, 1.0F);
    float screenRefreshRate = 60.0;
    int openglESVersion = 2;
    bool skipDuplicateFrames = false;
    bool immersiveModeEnabled = false;
    ImmersiveMode::Config immersiveModeConfig {};

    float defaultAspectRatio = 1.0;
    bool dirtyVideo = false;

    std::mutex coreLock;

    std::unique_ptr<Core> core;
    // retro_load_game deu certo: só então o destroy chama retro_unload_game.
    bool gameLoaded = false;
    // O que foi entregue no retro_game_info (conteúdo e caminho) e os códigos de trapaça: vivos até o destroy(),
    // porque o núcleo pode guardar os ponteiros.
    std::vector<int8_t> gameData;
    std::string gamePathStorage;
    std::list<std::string> cheatCodes;
    std::unique_ptr<Audio> audio;
    std::unique_ptr<Video> video;
    std::unique_ptr<FPSSync> fpsSync;
    std::unique_ptr<Input> input;
    // Protege [input]: pause/resume o trocam na thread principal enquanto eventos chegam pela thread GL.
    // Também protege a troca de [video] para quem a lê fora da thread GL (toque, shader).
    std::mutex inputLock;
    std::unique_ptr<Rumble> rumble;
    std::unique_ptr<Netplay> netplay;
    std::atomic<uint64_t> runCount {0};
    // Lidos de outras threads sem o coreLock (a emulação o segura durante todo o quadro).
    std::atomic<int64_t> netplayState {-1};
    std::atomic<uint32_t> netplayFrameCount {0};
    double contentFps = 60.0;

    Capture capture;
    int audioSampleRate = 0;

    std::atomic<bool> snapshotRequested {false};
    std::mutex snapshotLock;
    std::vector<int32_t> snapshot;
    void copySnapshot(const void *data, unsigned width, unsigned height, size_t pitch);
};

} //namespace libretrodroid

#endif //LIBRETRODROID_LIBRETRODROID_H
