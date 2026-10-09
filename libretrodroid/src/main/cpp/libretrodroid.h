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
#include <functional>
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

    // A memória do jogo que o app enxerga: a RAM do sistema (RETRO_MEMORY_SYSTEM_RAM) seguida das outras regiões graváveis
    // que o núcleo descreve em SET_MEMORY_MAPS (a EWRAM do GBA, por exemplo) e que não a sobrepõem. Os endereços do app
    // são posições nessa sequência. O tamanho é 0 quando o núcleo não expõe nada (ou passa de [MAX_SYSTEM_RAM]).
    size_t systemRamSize();
    // Tamanho de cada trecho da memória, na ordem (o primeiro é a RAM do sistema, se houver).
    std::vector<size_t> memorySegmentLengths();
    // Copia a memória toda para o destino, trecho a trecho, com o coreLock tomado e sem cópia intermediária: a RAM de um
    // PS2 tem 32 MB. O callback recebe o deslocamento e o trecho. Devolve o total copiado (0 se não há memória).
    size_t copySystemRam(const std::function<void(size_t, const uint8_t*, size_t)>& copy);
    // Copia só [offset, offset + length) (um buffer de texto): devolve quantos bytes entregou.
    size_t copyMemoryRange(size_t offset, size_t length, const std::function<void(size_t, const uint8_t*, size_t)>& copy);
    // Valores gravados na RAM depois de cada quadro do núcleo ("travar o valor"). Cada entrada: endereço, valor, largura
    // (1, 2 ou 4 bytes), ordem dos bytes e, no N64, a inversão por palavra. Troca a lista inteira; lista vazia desliga.
    struct RamFreeze {
        uint32_t address;
        uint32_t value;
        uint8_t width;
        bool bigEndian;
        // O RDRAM do N64 fica em palavras de 4 bytes na ordem do processador: o byte lógico N está em N ^ 3. O valor é
        // gravado do byte mais significativo para o menos, nessa numeração.
        bool wordSwap;
    };
    void setRamFreezes(std::vector<RamFreeze> freezes);
    // Grava [data] a partir de [offset] uma vez, depois do próximo quadro (a tradução dentro do jogo). Com [wordSwap] o
    // byte lógico N vai para N ^ 3.
    // Devolve se entrou na fila (cheia, ela recusa).
    bool queueMemoryWrite(size_t offset, std::vector<uint8_t> data, bool wordSwap);
    static constexpr size_t MAX_SYSTEM_RAM = 64u * 1024u * 1024u;
    static constexpr size_t MAX_PENDING_WRITES = 4096;
    static constexpr size_t MAX_PENDING_BYTES = 1024u * 1024u;

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
    /**
     * Um desenho: espera a hora do quadro (fora do coreLock), roda os quadros do núcleo e desenha. Devolve os
     * nanossegundos de trabalho (rodar + desenhar, sem a espera), ou 0 se foi só uma reapresentação do último quadro.
     */
    int64_t step();
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
    /** Quadros novos que o núcleo entregou (sem os repetidos, NULL): comparado com o runCount mostra quadros pulados. */
    uint64_t getVideoFrameCount() const { return videoFrameCount.load(); }
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
    /**
     * Taxa de atualização atual da tela (pode mudar com o app aberto: 60/90/120 Hz). Qualquer thread; vale a partir
     * do próximo desenho, na thread de emulação.
     */
    void setScreenRefreshRate(float rate) { pendingScreenRefreshRate = rate; }
    /**
     * Liga as dicas de avanço rápido para o núcleo (GET_FASTFORWARDING, GET_THROTTLE_STATE e o vídeo desligado
     * nos quadros intermediários). Só o avanço rápido do app liga: o teste de desempenho também usa
     * frameSpeed > 1, mas precisa de todos os quadros desenhados.
     */
    void setFastForwardHints(bool enabled);

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
    void replaceAudio(std::unique_ptr<Audio> newAudio, bool inheritStart = false);
    void publishRuntimeHints(unsigned speed);
    void reportAudioBufferStatus(unsigned speed);
    void applyAvTimingChange();
    void applyScreenRefreshChange();
    /** Reajusta a taxa do áudio ao ajuste de refresh do FPSSync atual (time stretch). */
    void updateAudioForPacing();
    void replaceFpsSync(std::unique_ptr<FPSSync> newFpsSync);
    /** Dorme até a hora do quadro, sem o coreLock, e diz quantos quadros do núcleo rodar (0 = reapresentar). */
    unsigned paceNextDraw();
    void runFrames(unsigned requestedFrames);
    void recreateAudio();
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
    std::atomic<unsigned int> frameSpeed {1};
    std::atomic<bool> fastForwardHints {false};
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
    // Trava de valores na RAM. Tomado depois do coreLock (o quadro aplica as travas) e solto antes de qualquer outro.
    std::mutex ramFreezeLock;
    std::vector<RamFreeze> ramFreezes;
    struct PendingWrite {
        size_t offset;
        std::vector<uint8_t> data;
        bool wordSwap;
    };
    std::vector<PendingWrite> pendingWrites;
    struct MemorySegment {
        uint8_t* data;
        size_t length;
    };
    // Os trechos da memória do jogo agora; quem chama segura o coreLock.
    std::vector<MemorySegment> memoryView();
    void applyRamFreezes();
    void logSystemRam(const char* when);
    // Só os núcleos que alocam a RAM tarde precisam do segundo registro; uma vez por jogo carregado.
    bool ramLogged = true;
    std::unique_ptr<Audio> audio;
    // Protege a troca do [audio] (recriado no step quando o núcleo muda a taxa de amostragem) para quem o usa
    // na thread principal (pause/resume/setFrameSpeed). Ordem: coreLock antes deste; nunca o contrário.
    std::mutex audioLock;
    std::unique_ptr<Video> video;
    std::unique_ptr<FPSSync> fpsSync;
    // Protege o ponteiro e o estado do [fpsSync]. A espera do quadro roda sem o coreLock (senão a thread principal
    // ficaria presa até um quadro inteiro em leituras de SRAM, viewport, destroy…), então o destroy() e as trocas do
    // FPSSync pegam este lock também. Ordem: coreLock antes deste; quem dorme nunca o segura.
    std::mutex pacingLock;
    std::atomic<float> pendingScreenRefreshRate {0.0F};
    std::unique_ptr<Input> input;
    // Protege [input]: pause/resume o trocam na thread principal enquanto eventos chegam pela thread GL.
    // Também protege a troca de [video] para quem a lê fora da thread GL (toque, shader).
    std::mutex inputLock;
    std::unique_ptr<Rumble> rumble;
    std::unique_ptr<Netplay> netplay;
    std::atomic<uint64_t> runCount {0};
    std::atomic<uint64_t> videoFrameCount {0};
    // Lidos de outras threads sem o coreLock (a emulação o segura durante todo o quadro).
    std::atomic<int64_t> netplayState {-1};
    std::atomic<uint32_t> netplayFrameCount {0};
    double contentFps = 60.0;
    // Taxa de amostragem que o núcleo informou (antes do ajuste ao refresh da tela), para notar quando muda.
    double contentSampleRate = 0.0;
    // Latência mínima (SET_MINIMUM_AUDIO_LATENCY) com que o Audio atual foi criado.
    unsigned appliedMinimumLatencyMs = 0;

    Capture capture;
    int audioSampleRate = 0;

    std::atomic<bool> snapshotRequested {false};
    std::mutex snapshotLock;
    std::vector<int32_t> snapshot;
    void copySnapshot(const void *data, unsigned width, unsigned height, size_t pitch);
};

} //namespace libretrodroid

#endif //LIBRETRODROID_LIBRETRODROID_H
