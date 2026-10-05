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

#include <algorithm>
#include <cmath>
#include <jni.h>

#include <EGL/egl.h>

#include <string>
#include <utility>
#include <vector>
#include <unordered_set>

#include "libretrodroid.h"
#include "utils/libretrodroidexception.h"
#include "log.h"
#include "core.h"
#include "audio.h"
#include "video.h"
#include "renderers/renderer.h"
#include "fpssync.h"
#include "input.h"
#include "rumble.h"
#include "shadermanager.h"
#include "utils/javautils.h"
#include "environment.h"
#include "renderers/es3/framebufferrenderer.h"
#include "renderers/es2/imagerendereres2.h"
#include "renderers/es3/imagerendereres3.h"
#include "utils/utils.h"
#include "utils/rect.h"
#include "errorcodes.h"
#include "vfs/vfs.h"
#include "vulkan/vulkancontext.h"

namespace libretrodroid {

uintptr_t LibretroDroid::callback_get_current_framebuffer() {
    return LibretroDroid::getInstance().handleGetCurrentFrameBuffer();
}

void LibretroDroid::callback_hw_video_refresh(
    const void *data,
    unsigned width,
    unsigned height,
    size_t pitch
) {
    LOGD("hw video refresh callback called %i %i", width, height);
    LibretroDroid::getInstance().handleVideoRefresh(data, width, height, pitch);
}

void LibretroDroid::callback_audio_sample(int16_t left, int16_t right) {
    LOGE("callback audio sample (left, right) has been called");
}

size_t LibretroDroid::callback_set_audio_sample_batch(const int16_t *data, size_t frames) {
    return LibretroDroid::getInstance().handleAudioCallback(data, frames);
}

void LibretroDroid::callback_retro_set_input_poll() {
    // Do nothing in here...
}

int16_t LibretroDroid::callback_set_input_state(
    unsigned int port,
    unsigned int device,
    unsigned int index,
    unsigned int id
) {
    return LibretroDroid::getInstance().handleSetInputState(port, device, index, id);
}

void LibretroDroid::updateAudioSampleRateMultiplier() {
    // Chamado da thread principal (setFrameSpeed) enquanto o step pode estar trocando o Audio.
    std::lock_guard<std::mutex> lock(audioLock);
    if (audio) {
        audio->setPlaybackSpeed(frameSpeed.load());
    }
}

void LibretroDroid::replaceAudio(std::unique_ptr<Audio> newAudio, bool inheritStart) {
    std::unique_ptr<Audio> oldAudio;
    {
        // A troca, a velocidade e o start/stop andam juntos sob o audioLock: o pause()/resume() da thread
        // principal então enxergam sempre o Audio atual, e um pause() que chegue no meio não deixa o novo tocando.
        // O antigo é destruído fora do lock, porque fechar o stream pode demorar.
        std::lock_guard<std::mutex> lock(audioLock);
        bool wasStarted = audio && audio->isStartRequested();
        if (newAudio) {
            newAudio->setPlaybackSpeed(frameSpeed.load());
        }
        oldAudio = std::move(audio);
        audio = std::move(newAudio);
        if (oldAudio && wasStarted) oldAudio->stop();
        if (audio && inheritStart && wasStarted) audio->start();
    }
    oldAudio = nullptr;
}

// TODO... Do we really need this?
void LibretroDroid::resetGlobalVariables() {
    core = nullptr;
    gameLoaded = false;
    replaceAudio(nullptr);
    replaceVideo(nullptr);
    replaceFpsSync(nullptr);
    {
        std::lock_guard<std::mutex> lock(inputLock);
        input = nullptr;
    }
    rumble = nullptr;
}

void LibretroDroid::replaceVideo(std::unique_ptr<Video> newVideo) {
    std::unique_ptr<Video> oldVideo;
    {
        // O toque (thread principal) lê a Video com este lock; a antiga é destruída fora dele, porque soltar
        // os quadros do Vulkan pode esperar a GPU.
        std::lock_guard<std::mutex> lock(inputLock);
        oldVideo = std::move(video);
        video = std::move(newVideo);
    }
    oldVideo = nullptr;
}

int LibretroDroid::availableDisks() {
    return Environment::getInstance().getRetroDiskControlCallback() != nullptr
           ? Environment::getInstance().getRetroDiskControlCallback()->get_num_images()
           : 0;
}

int LibretroDroid::currentDisk() {
    return Environment::getInstance().getRetroDiskControlCallback() != nullptr
           ? Environment::getInstance().getRetroDiskControlCallback()->get_image_index()
           : 0;
}

void LibretroDroid::changeDisk(unsigned int index) {
    if (Environment::getInstance().getRetroDiskControlCallback() == nullptr) {
        LOGE("Cannot swap disk. This platform does not support it.");
        return;
    }

    if (index < 0 || index >= Environment::getInstance().getRetroDiskControlCallback()->get_num_images()) {
        LOGE("Requested image index is not valid.");
        return;
    }

    if (Environment::getInstance().getRetroDiskControlCallback()->get_image_index() != index) {
        Environment::getInstance().getRetroDiskControlCallback()->set_eject_state(true);
        Environment::getInstance().getRetroDiskControlCallback()->set_image_index((unsigned) index);
        Environment::getInstance().getRetroDiskControlCallback()->set_eject_state(false);
    }
}

void LibretroDroid::updateVariable(const Variable& variable) {
    Environment::getInstance().updateVariable(variable.key, variable.value);
}

std::vector<Variable> LibretroDroid::getVariables() {
    return Environment::getInstance().getVariables();
}

std::vector<std::vector<struct Controller>> LibretroDroid::getControllers() {
    return Environment::getInstance().getControllers();
}

void LibretroDroid::setControllerType(unsigned int port, unsigned int type) {
    if (!core) return;
    core->retro_set_controller_port_device(port, type);
}

bool LibretroDroid::unserializeState(int8_t *data, size_t size) {
    std::lock_guard<std::mutex> lock(coreLock);

    if (!core) return false;

    return core->retro_unserialize(data, size);
}

JNIEXPORT jboolean JNICALL LibretroDroid::unserializeSRAM(int8_t* data, size_t size) {
    std::lock_guard<std::mutex> lock(coreLock);

    if (!core) return false;

    size_t sramSize = core->retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void *sramState = core->retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);

    if (sramState == nullptr) {
        LOGE("Cannot load SRAM: nullptr in retro_get_memory_data");
        return false;
    }

    if (size > sramSize) {
        LOGE("Cannot load SRAM: size mismatch");
        return false;
    }

    memcpy(sramState, data, size);

    return true;
}

std::vector<int8_t> LibretroDroid::serializeSRAM() {
    std::lock_guard<std::mutex> lock(coreLock);

    if (!core) return {};

    size_t size = core->retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    auto* source = (int8_t*) core->retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    // Alguns núcleos informam tamanho > 0 sem ponteiro: devolve vazio em vez de ler de nullptr.
    if (source == nullptr || size == 0) {
        return {};
    }

    return std::vector<int8_t>(source, source + size);
}

void LibretroDroid::onSurfaceChanged(unsigned int width, unsigned int height) {
    LOGD("Performing libretrodroid onSurfaceChanged");
    // Thread GL, enquanto o destroy() (thread principal) pode estar soltando a Video.
    std::lock_guard<std::mutex> lock(coreLock);
    if (!video) return;
    video->updateScreenSize(width, height);
}

void LibretroDroid::onSurfaceCreated() {
    LOGD("Performing libretrodroid onSurfaceCreated");

    // Com o coreLock, como o carregamento: o destroy() pode rodar entre o fim do retro_load_game e esta
    // chamada, e descarregar o núcleo (e o Vulkan) no meio do context_reset. Nada chamado daqui pega esse lock.
    std::lock_guard<std::mutex> lock(coreLock);
    if (!core) return;

    struct retro_system_av_info system_av_info {};
    core->retro_get_system_av_info(&system_av_info);

    replaceVideo(nullptr);

    // Hardware cores may render anywhere up to max_width x max_height (the actual size of every
    // frame comes with the video callback), so their framebuffer has to be that big.
    Video::RenderingOptions renderingOptions {
        Environment::getInstance().isUseHwAcceleration(),
        std::max(system_av_info.geometry.base_width, system_av_info.geometry.max_width),
        std::max(system_av_info.geometry.base_height, system_av_info.geometry.max_height),
        Environment::getInstance().isUseDepth(),
        Environment::getInstance().isUseStencil(),
        openglESVersion,
        Environment::getInstance().getPixelFormat(),
        Environment::getInstance().isUseVulkan()
    };

    // O núcleo Vulkan precisa do dispositivo antes do context_reset (é quando ele pede a interface).
    if (Environment::getInstance().isUseVulkan() && !VulkanContext::getInstance().create()) {
        throw LibretroDroidError("Cannot create the Vulkan context for this core", ERROR_GL_NOT_COMPATIBLE);
    }

    replaceVideo(std::make_unique<Video>(
        renderingOptions,
        fragmentShaderConfig,
        Environment::getInstance().isBottomLeftOrigin(),
        Environment::getInstance().getScreenRotation(),
        skipDuplicateFrames,
        immersiveModeEnabled,
        viewportRect,
        immersiveModeConfig
    ));

    if (Environment::getInstance().getHwContextReset() != nullptr) {
        Environment::getInstance().getHwContextReset()();
    }
}

void LibretroDroid::onMotionEvent(
    unsigned int port,
    unsigned int source,
    float xAxis,
    float yAxis
) {
    LOGD("Received motion event: %d %.2f, %.2f", source, xAxis, yAxis);
    std::lock_guard<std::mutex> lock(inputLock);
    if (input) {
        input->onMotionEvent(port, source, xAxis, yAxis);
    }
}

void LibretroDroid::onTouchEvent(float xAxis, float yAxis) {
    LOGD("Received touch event: %.2f, %.2f", xAxis, yAxis);
    std::lock_guard<std::mutex> lock(inputLock);
    if (input && video) {
        auto [x, y] = video->getLayout().getRelativePosition(xAxis, yAxis);
        input->onMotionEvent(0, Input::MOTION_SOURCE_POINTER, x, y);
    }
}

void LibretroDroid::onKeyEvent(unsigned int port, int action, int keyCode) {
    LOGD("Received key event with action (%d) and keycode (%d)", action, keyCode);
    std::lock_guard<std::mutex> lock(inputLock);
    if (input) {
        input->onKeyEvent(port, action, keyCode);
    }
}

void LibretroDroid::create(
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
) {
    LOGD("Performing libretrodroid create");

    resetGlobalVariables();

    Environment::getInstance().initialize(systemDir, savesDir, &callback_get_current_framebuffer);
    Environment::getInstance().setLanguage(language);
    Environment::getInstance().setEnableVirtualFileSystem(enableVirtualFileSystem);
    Environment::getInstance().setEnableMicrophone(enableMicrophone);
    Environment::getInstance().setTargetRefreshRate(refreshRate);

    openglESVersion = GLESVersion;
    screenRefreshRate = refreshRate;
    // O create já traz a taxa lida agora: um aviso anterior de outro jogo não vale mais.
    pendingScreenRefreshRate = 0.0F;
    skipDuplicateFrames = duplicateFrames;
    immersiveModeEnabled = GLESVersion >= 3 && immersiveModeConfig.has_value();
    this->immersiveModeConfig = immersiveModeConfig.value_or(ImmersiveMode::Config{});
    audioEnabled = true;
    frameSpeed = 1;
    fastForwardHints = false;

    core = std::make_unique<Core>(soFilePath);

    core->retro_set_environment(&Environment::callback_environment);

    std::for_each(variables.begin(), variables.end(), [&](const Variable& v) {
        updateVariable(v);
    });

    core->retro_init();

    // Same order as RetroArch: the libretro API only guarantees these before the first retro_run,
    // and some cores (Mesen) dereference state created in retro_init when they are set.
    core->retro_set_video_refresh(&callback_hw_video_refresh);
    core->retro_set_audio_sample(&callback_audio_sample);
    core->retro_set_audio_sample_batch(&callback_set_audio_sample_batch);
    core->retro_set_input_poll(&callback_retro_set_input_poll);
    core->retro_set_input_state(&callback_set_input_state);

    preferLowLatencyAudio = lowLatencyAudio;

    // HW accelerated cores are only supported on opengles 3.
    if ((Environment::getInstance().isUseHwAcceleration() || Environment::getInstance().isUseVulkan()) && openglESVersion < 3) {
        throw LibretroDroidError("OpenGL ES 3 is required for this Core", ERROR_GL_NOT_COMPATIBLE);
    }

    fragmentShaderConfig = shaderConfig;

    rumble = std::make_unique<Rumble>();
}

void LibretroDroid::throwLoadGameError() {
    // The core gave up because the GPU could not provide the context it needs: say so.
    if (Environment::getInstance().isHwContextRejected()) {
        LOGE("Cannot load game: unsupported hardware context. Leaving.");
        throw LibretroDroidError("The GPU does not support the context this core requires", ERROR_GL_NOT_COMPATIBLE);
    }
    LOGE("Cannot load game. Leaving.");
    throw std::runtime_error("Cannot load game");
}

void LibretroDroid::throwIfCoreMissing() {
    // O núcleo foi destruído antes de o carregamento começar (a tela saiu durante a preparação).
    if (!core) {
        LOGE("Cannot load game: the core was already destroyed. Leaving.");
        throw std::runtime_error("Core already destroyed");
    }
}

void LibretroDroid::throwIfHwContextMissing() {
    // Alguns núcleos (o Play!) ignoram a recusa do SET_HW_RENDER e dizem que carregaram: sem contexto,
    // caíam segundos depois ao usar o vídeo que nunca foi criado.
    if (Environment::getInstance().isHwContextRejected() && !Environment::getInstance().isHwContextAccepted()) {
        LOGE("Game loaded without the hardware context it asked for. Leaving.");
        throw LibretroDroidError("The GPU does not support the context this core requires", ERROR_GL_NOT_COMPATIBLE);
    }
}

void LibretroDroid::loadGameFromPath(const std::string& gamePath) {
    LOGD("Performing libretrodroid loadGameFromPath");
    // Com o coreLock: um destroy() (sair durante o carregamento) espera o retro_load_game terminar em
    // vez de descarregar o núcleo no meio dele. Os callbacks chamados pelo núcleo não pegam esse lock.
    std::lock_guard<std::mutex> lock(coreLock);
    throwIfCoreMissing();
    struct retro_system_info system_info {};
    core->retro_get_system_info(&system_info);

    struct retro_game_info game_info {};
    gamePathStorage = gamePath;
    game_info.path = gamePathStorage.c_str();
    game_info.meta = nullptr;

    if (system_info.need_fullpath) {
        game_info.data = nullptr;
        game_info.size = 0;
    } else {
        gameData = Utils::readFileAsBytes(gamePath);
        game_info.data = gameData.data();
        game_info.size = gameData.size();
    }

    bool result = core->retro_load_game(&game_info);
    if (!result) {
        throwLoadGameError();
    }
    gameLoaded = true;
    throwIfHwContextMissing();

    afterGameLoad();
}

void LibretroDroid::loadGameFromBytes(std::vector<int8_t> data) {
    LOGD("Performing libretrodroid loadGameFromBytes");
    // Com o coreLock: um destroy() (sair durante o carregamento) espera o retro_load_game terminar em
    // vez de descarregar o núcleo no meio dele. Os callbacks chamados pelo núcleo não pegam esse lock.
    std::lock_guard<std::mutex> lock(coreLock);
    throwIfCoreMissing();

    struct retro_system_info system_info {};
    core->retro_get_system_info(&system_info);

    struct retro_game_info game_info {};
    game_info.path = nullptr;
    game_info.meta = nullptr;

    if (system_info.need_fullpath) {
        game_info.data = nullptr;
        game_info.size = 0;
    } else {
        // Fica com o LibretroDroid até o destroy(): alguns núcleos usam o buffer depois do retro_load_game.
        gameData = std::move(data);
        game_info.data = gameData.data();
        game_info.size = gameData.size();
    }

    bool result = core->retro_load_game(&game_info);
    if (!result) {
        throwLoadGameError();
    }
    gameLoaded = true;
    throwIfHwContextMissing();

    afterGameLoad();
}

void LibretroDroid::loadGameFromVirtualFiles(std::vector<VFSFile> virtualFiles) {
    LOGD("Performing libretrodroid loadGameFromVirtualFiles");
    // Com o coreLock: um destroy() (sair durante o carregamento) espera o retro_load_game terminar em
    // vez de descarregar o núcleo no meio dele. Os callbacks chamados pelo núcleo não pegam esse lock.
    std::lock_guard<std::mutex> lock(coreLock);
    throwIfCoreMissing();
    struct retro_system_info system_info {};
    core->retro_get_system_info(&system_info);

    if (virtualFiles.empty()) {
        LOGE("Calling loadGameFromVirtualFiles without any file.");
        throw std::runtime_error("Calling loadGameFromVirtualFiles without any file.");
    }

    std::string firstFilePath = virtualFiles[0].getFileName();
    int firstFileFD = virtualFiles[0].getFD();

    bool loadUsingVFS = system_info.need_fullpath || virtualFiles.size() > 1;

    struct retro_game_info game_info {};
    gamePathStorage = firstFilePath;
    game_info.path = gamePathStorage.c_str();
    game_info.meta = nullptr;

    if (loadUsingVFS) {
        VFS::getInstance().initialize(std::move(virtualFiles));
    }

    if (loadUsingVFS) {
        game_info.data = nullptr;
        game_info.size = 0;
    } else {
        gameData = Utils::readFileAsBytes(firstFileFD);
        game_info.data = gameData.data();
        game_info.size = gameData.size();
    }

    bool result = core->retro_load_game(&game_info);
    if (!result) {
        throwLoadGameError();
    }
    gameLoaded = true;
    throwIfHwContextMissing();

    afterGameLoad();
}

void LibretroDroid::destroy() {
    std::lock_guard<std::mutex> lock(coreLock);

    LOGD("Performing libretrodroid destroy");

    // Já destruído (ou o create falhou antes de criar o núcleo): não há o que descarregar.
    if (!core) {
        return;
    }

    // Sem jogo carregado (destruído antes ou durante um carregamento que falhou) não há contexto nem
    // jogo a descarregar: só o retro_deinit, como o RetroArch faz.
    if (gameLoaded) {
        // Sempre, mesmo que o context_reset não tenha rodado (a criação do contexto Vulkan pode falhar antes
        // dele): é o context_destroy que encerra as threads de vídeo do núcleo, e o PPSSPP aborta no dlclose
        // com uma std::thread ainda ativa se ele não vier.
        if (Environment::getInstance().getHwContextDestroy() != nullptr) {
            Environment::getInstance().getHwContextDestroy()();
        }
        core->retro_unload_game();
    }
    gameLoaded = false;
    core->retro_deinit();

    // Só depois do retro_unload_game/retro_deinit: o núcleo pode usar os dados e o caminho do jogo até ali.
    std::vector<int8_t>().swap(gameData);
    gamePathStorage.clear();
    cheatCodes.clear();

    capture.release();
    capture.setAudioEnabled(false);

    replaceVideo(nullptr);
    // Depois do retro_deinit e antes de soltar o núcleo: o destroy_device dele roda aqui.
    VulkanContext::getInstance().destroy();
    core = nullptr;
    rumble = nullptr;
    netplay = nullptr;
    netplayState = -1;
    // Um step dormindo (sem o coreLock) acorda, acha o FPSSync nulo e depois o núcleo nulo, e volta.
    replaceFpsSync(nullptr);
    replaceAudio(nullptr);

    Environment::getInstance().deinitialize();
    VFS::getInstance().deinitialize();
}

void LibretroDroid::resume() {
    LOGD("Performing libretrodroid resume");

    {
        // Eventos da fila da thread GL (controles pela rede) rodam mesmo com ela pausada, enquanto
        // pause/resume trocam o Input na thread principal.
        std::lock_guard<std::mutex> lock(inputLock);
        input = std::make_unique<Input>();
    }

    {
        // O step pode recriar o FPSSync (mudança de fps do núcleo) e a espera usa o estado dele sem o coreLock.
        std::lock_guard<std::mutex> lock(pacingLock);
        if (fpsSync) fpsSync->reset();
    }
    {
        std::lock_guard<std::mutex> lock(audioLock);
        if (audio) audio->start();
    }
    refreshAspectRatio();
}

void LibretroDroid::pause() {
    LOGD("Performing libretrodroid pause");
    {
        std::lock_guard<std::mutex> lock(audioLock);
        if (audio) audio->stop();
    }

    std::lock_guard<std::mutex> lock(inputLock);
    input = nullptr;
}

void LibretroDroid::replaceFpsSync(std::unique_ptr<FPSSync> newFpsSync) {
    std::lock_guard<std::mutex> lock(pacingLock);
    fpsSync = std::move(newFpsSync);
}

unsigned LibretroDroid::paceNextDraw() {
    // Dorme ANTES de rodar o quadro, até a hora marcada: o quadro pronto é apresentado assim que o step volta (antes
    // ele esperava a sobra do intervalo depois de pronto, e a imagem chegava à tela um quadro mais tarde). Sem o
    // coreLock, para a thread principal não ficar presa na espera.
    TimePoint wakeAt = TimePoint::min();
    {
        std::lock_guard<std::mutex> lock(pacingLock);
        if (fpsSync) wakeAt = fpsSync->nextStart();
    }
    if (wakeAt != TimePoint::min()) {
        std::this_thread::sleep_until(wakeAt);
    }

    std::lock_guard<std::mutex> lock(pacingLock);
    return fpsSync ? fpsSync->advanceFrames() : 1;
}

int64_t LibretroDroid::step() {
    const unsigned requestedFrames = paceNextDraw();

    std::lock_guard<std::mutex> lock(coreLock);

    // Um desenho atrasado depois do destroy() não tem núcleo para rodar.
    if (!core) return 0;

    const auto workStart = std::chrono::steady_clock::now();

    if (requestedFrames == 0) {
        // Tela múltipla do conteúdo (120 Hz com 60 fps): o GLSurfaceView troca os buffers depois de todo desenho e o
        // conteúdo do buffer novo é indefinido, então os vsyncs sem quadro novo redesenham o último. Não conta como
        // quadro novo: sem captura, sem skipDuplicateFrames e sem tempo de trabalho para o governador de energia.
        if (video) {
            video->representFrame();
        }
        applyScreenRefreshChange();
        return 0;
    }

    runFrames(requestedFrames);

    // Por último: o quadro que trouxe o timing novo já foi marcado com o antigo.
    if (Environment::getInstance().isAvTimingUpdated()) {
        applyAvTimingChange();
    }
    applyScreenRefreshChange();

    // Núcleos como o PCSX ReARMed pedem a latência mínima já dentro do retro_run (com o frameskip automático).
    // A API prevê a reinicialização do áudio nesse caso; o valor só difere do aplicado quando muda de verdade.
    if (Environment::getInstance().getMinimumAudioLatency() != appliedMinimumLatencyMs) {
        recreateAudio();
    }

    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - workStart).count();
}

void LibretroDroid::runFrames(unsigned requestedFrames) {
    LOGD("Stepping into retro_run()");

    // Uma leitura só: o setFrameSpeed vem da thread principal e o quadro inteiro precisa da mesma velocidade.
    const unsigned speed = frameSpeed.load();
    auto& environment = Environment::getInstance();
    publishRuntimeHints(speed);

    // Nos quadros intermediários do avanço rápido o núcleo pode nem gerar vídeo (o resto é descartado de qualquer
    // jeito). Só núcleos de software: pular o desenho de um núcleo de GPU nunca foi testado aqui.
    const bool dropIntermediateVideo = fastForwardHints && speed > 1 && !netplay &&
        !environment.isUseHwAcceleration() && !environment.isUseVulkan();

    // If the application runs too slow it's better to just skip those frames.
    const unsigned frames = std::min(requestedFrames, 2u);

    auto& frameTime = Environment::getInstance().getFrameTimeCallback();
    const size_t totalRuns = (size_t) frames * speed;
    for (size_t i = 0; i < totalRuns; i++) {
        // Em rede, o quadro só roda com a entrada dos dois lados; sem a do outro, fica para o próximo desenho.
        if (netplay) {
            Netplay::Pad localPad;
            {
                // O pause() troca o Input na thread principal.
                std::lock_guard<std::mutex> inputGuard(inputLock);
                localPad = netplay->capture(input.get());
            }
            if (!netplay->prepareFrame(localPad)) {
                break;
            }
        }
        environment.setVideoEnabled(!dropIntermediateVideo || i + 1 == totalRuns);
        reportAudioBufferStatus(speed);
        // Each retro_run is one frame of emulated time, so the reference duration is the right delta
        // (fast-forward runs more frames, not longer ones).
        if (frameTime.callback != nullptr) {
            frameTime.callback(frameTime.reference);
        }
        core->retro_run();
        runCount++;
        if (netplay) {
            netplay->frameDone();
        }
    }
    environment.setVideoEnabled(true);

    if (netplay) {
        netplayState = netplay->isBroken() ? -2 : netplay->stalledMillis();
        netplayFrameCount = netplay->currentFrame();
    }

    if (video && !video->rendersInVideoCallback()) {
        video->renderFrame();
    }

    if (video && video->takeFrameRendered()) {
        capture.onFrameRendered(*video);
    }

    if (rumble && rumbleEnabled) {
        rumble->fetchFromEnvironment();
    }

    // Some games override the core geometry at runtime. These fields get updated in retro_run().
    if (video && Environment::getInstance().isGameGeometryUpdated()) {
        Environment::getInstance().clearGameGeometryUpdated();

        video->updateRendererSize(
            std::max(Environment::getInstance().getGameGeometryWidth(), Environment::getInstance().getGameGeometryMaxWidth()),
            std::max(Environment::getInstance().getGameGeometryHeight(), Environment::getInstance().getGameGeometryMaxHeight())
        );

        dirtyVideo = true;
    }

    if (video && Environment::getInstance().isScreenRotationUpdated()) {
        Environment::getInstance().clearScreenRotationUpdated();

        video->updateRotation(Environment::getInstance().getScreenRotation());
    }
}

void LibretroDroid::recreateAudio() {
    auto& environment = Environment::getInstance();
    appliedMinimumLatencyMs = environment.getMinimumAudioLatency();
    replaceAudio(
        std::make_unique<Audio>(audioSampleRate, contentFps, preferLowLatencyAudio, appliedMinimumLatencyMs),
        true
    );
}

void LibretroDroid::publishRuntimeHints(unsigned speed) {
    auto& environment = Environment::getInstance();
    bool fastForwarding = fastForwardHints && speed > 1;
    environment.setFastForwarding(fastForwarding);

    if (fastForwarding) {
        environment.setThrottleState(RETRO_THROTTLE_FAST_FORWARD, (float) (contentFps * speed));
    } else if (fpsSync && fpsSync->isUsingVSync()) {
        // O FPSSync não espera nada: quem marca o ritmo é o vsync da tela (dividido, se o núcleo roda a cada n vsyncs).
        environment.setThrottleState(RETRO_THROTTLE_VSYNC, (float) fpsSync->getFrameRate());
    } else {
        environment.setThrottleState(RETRO_THROTTLE_NONE, (float) contentFps);
    }
}

void LibretroDroid::reportAudioBufferStatus(unsigned speed) {
    auto& callback = Environment::getInstance().getAudioBufferStatusCallback();
    if (callback.callback == nullptr) return;

    // Os núcleos pulam quadros quando a fila está baixa. Sem sentido (e prejudicial) se a fila não é consumida de
    // verdade: som desligado (o teste de desempenho roda mudo e a fila esvazia, o que inflaria a medição), sem
    // saída de áudio, avanço rápido (a fila esvazia por definição) e partida em rede (cada lado tem de rodar
    // todos os quadros).
    int occupancy = -1;
    if (audioEnabled && speed <= 1 && !netplay && audio) {
        occupancy = audio->bufferOccupancy();
    }

    if (occupancy < 0) {
        callback.callback(false, 0, false);
        return;
    }
    // O controle do Audio mira em 50% da fila; abaixo de um quarto ela está na metade do alvo e a próxima
    // rajada de amostras pode secar o buffer. Mais sensível pularia quadros por flutuações normais.
    constexpr int UNDERRUN_LIKELY_BELOW = 25;
    callback.callback(true, (unsigned) occupancy, occupancy < UNDERRUN_LIKELY_BELOW);
}

void LibretroDroid::applyAvTimingChange() {
    auto& environment = Environment::getInstance();
    environment.clearAvTimingUpdated();

    double fps = environment.getAvTimingFps();
    double sampleRate = environment.getAvTimingSampleRate();
    if (!(fps > 0) || !(sampleRate > 0)) return;

    // Muitos núcleos repetem o SET_SYSTEM_AV_INFO só para mudar a geometria: sem mudança de timing, nada a refazer.
    bool fpsChanged = std::abs(fps - contentFps) > 0.001;
    bool rateChanged = std::abs(sampleRate - contentSampleRate) > 0.5;
    if (!fpsChanged && !rateChanged) return;

    LOGI("AV timing changed: fps %f -> %f, sample rate %f -> %f", contentFps, fps, contentSampleRate, sampleRate);

    // Estamos na thread de emulação, com o coreLock: só o step e o destroy() trocam o fpsSync.
    if (fpsChanged) {
        contentFps = fps;
        replaceFpsSync(std::make_unique<FPSSync>(contentFps, screenRefreshRate));
    }
    contentSampleRate = sampleRate;
    updateAudioForPacing();
}

void LibretroDroid::applyScreenRefreshChange() {
    float pending = pendingScreenRefreshRate.exchange(0.0F);
    if (!(pending > 0) || std::abs(pending - screenRefreshRate) < 0.01F) return;

    LOGI("Screen refresh rate changed: %f -> %f", screenRefreshRate, pending);
    screenRefreshRate = pending;
    Environment::getInstance().setTargetRefreshRate(pending);
    // Sem jogo carregado o afterGameLoad já usa a taxa nova.
    if (fpsSync) {
        replaceFpsSync(std::make_unique<FPSSync>(contentFps, screenRefreshRate));
        updateAudioForPacing();
    }
}

void LibretroDroid::updateAudioForPacing() {
    // O ajuste ao refresh da tela (time stretch) depende do fps e da tela, então ele também pode mexer na taxa do áudio.
    int newAudioRate = (int) std::lround(contentSampleRate * fpsSync->getTimeStretchFactor());
    if (newAudioRate != audioSampleRate) {
        audioSampleRate = newAudioRate;
        recreateAudio();
    }
}

float LibretroDroid::getAspectRatio() {
    float gameAspectRatio = Environment::getInstance().retrieveGameSpecificAspectRatio();
    return gameAspectRatio > 0 ? gameAspectRatio : defaultAspectRatio;
}

void LibretroDroid::refreshAspectRatio() {
    // Chamado da thread principal (resume) e da GL, enquanto o destroy() pode soltar a Video.
    std::lock_guard<std::mutex> lock(coreLock);
    if (!video) return;
    video->updateAspectRatio(getAspectRatio());
}

void LibretroDroid::setRumbleEnabled(bool enabled) {
    rumbleEnabled = enabled;
}

bool LibretroDroid::isRumbleEnabled() const {
    return rumbleEnabled;
}

void LibretroDroid::setFrameSpeed(unsigned int speed) {
    frameSpeed = speed;
    updateAudioSampleRateMultiplier();
}

void LibretroDroid::setFastForwardHints(bool enabled) {
    fastForwardHints = enabled;
}

void LibretroDroid::setAudioEnabled(bool enabled) {
    audioEnabled = enabled;
}

void LibretroDroid::setShaderConfig(ShaderManager::Config shaderConfig) {
    fragmentShaderConfig = std::move(shaderConfig);
    // Thread principal: o lock segura a Video viva enquanto a thread GL pode trocá-la.
    std::lock_guard<std::mutex> lock(inputLock);
    if (video) {
        video->updateShaderType(fragmentShaderConfig);
    }
}

void LibretroDroid::handleVideoRefresh(
    const void *data,
    unsigned int width,
    unsigned int height,
    size_t pitch
) {
    // Quadro intermediário do avanço rápido: o núcleo que ignorou o pedido para não gerar vídeo não paga o
    // envio da textura nem o desenho (a API manda este callback não fazer nada).
    if (!Environment::getInstance().isVideoEnabled()) return;

    // Só quadros novos (os repetidos vêm com NULL): é o que mostra, contra o runCount, quadros pulados pelo núcleo.
    if (data != nullptr) {
        videoFrameCount++;
    }
    if (snapshotRequested.load()) {
        copySnapshot(data, width, height, pitch);
    }
    if (video) {
        video->onNewFrame(data, width, height, pitch);

        if (video->rendersInVideoCallback()) {
            video->renderFrame();
        }
    }
}

void LibretroDroid::requestFrameSnapshot() {
    std::lock_guard<std::mutex> lock(snapshotLock);
    snapshot.clear();
    snapshotRequested = true;
}

std::vector<int32_t> LibretroDroid::takeFrameSnapshot() {
    std::lock_guard<std::mutex> lock(snapshotLock);
    std::vector<int32_t> result;
    result.swap(snapshot);
    return result;
}

void LibretroDroid::copySnapshot(const void *data, unsigned width, unsigned height, size_t pitch) {
    // Quadro repetido (NULL): espera o próximo desenhado.
    if (data == nullptr || width == 0 || height == 0) return;
    std::vector<int32_t> out(6, 0);
    if (data != RETRO_HW_FRAME_BUFFER_VALID && video) {
        // Área do jogo na view, em pixels com a origem no topo (os vértices estão em NDC, y para cima).
        auto& vertices = video->getLayout().getForegroundVertices();
        float minX = 1.0F, maxX = -1.0F, minY = 1.0F, maxY = -1.0F;
        for (size_t i = 0; i < vertices.size(); i += 2) {
            minX = std::min(minX, vertices[i]);
            maxX = std::max(maxX, vertices[i]);
            minY = std::min(minY, vertices[i + 1]);
            maxY = std::max(maxY, vertices[i + 1]);
        }
        int screenWidth = video->getLayout().getScreenWidth();
        int screenHeight = video->getLayout().getScreenHeight();
        out[0] = (int32_t) width;
        out[1] = (int32_t) height;
        out[2] = (int32_t) std::lround((minX + 1.0F) * 0.5F * screenWidth);
        out[3] = (int32_t) std::lround((1.0F - maxY) * 0.5F * screenHeight);
        out[4] = (int32_t) std::lround((maxX + 1.0F) * 0.5F * screenWidth);
        out[5] = (int32_t) std::lround((1.0F - minY) * 0.5F * screenHeight);
        out.resize(6 + (size_t) width * height);
        int format = Environment::getInstance().getPixelFormat();
        const auto* bytes = static_cast<const uint8_t*>(data);
        for (unsigned y = 0; y < height; y++) {
            int32_t* row = out.data() + 6 + (size_t) y * width;
            if (format == RETRO_PIXEL_FORMAT_XRGB8888) {
                auto* src = reinterpret_cast<const uint32_t*>(bytes + y * pitch);
                for (unsigned x = 0; x < width; x++) row[x] = (int32_t) (0xFF000000u | (src[x] & 0xFFFFFFu));
            } else {
                auto* src = reinterpret_cast<const uint16_t*>(bytes + y * pitch);
                bool is565 = format == RETRO_PIXEL_FORMAT_RGB565;
                for (unsigned x = 0; x < width; x++) {
                    uint32_t p = src[x];
                    uint32_t r = is565 ? (p >> 11) & 31 : (p >> 10) & 31;
                    uint32_t g = is565 ? ((p >> 5) & 63) * 255 / 63 : ((p >> 5) & 31) * 255 / 31;
                    uint32_t b = p & 31;
                    row[x] = (int32_t) (0xFF000000u | ((r * 255 / 31) << 16) | (g << 8) | (b * 255 / 31));
                }
            }
        }
    }
    std::lock_guard<std::mutex> lock(snapshotLock);
    snapshot.swap(out);
    snapshotRequested = false;
}

size_t LibretroDroid::handleAudioCallback(const int16_t *data, size_t frames) {
    // Antes do "som ligado": transmitindo, o celular pode ficar mudo e a outra tela continuar com som.
    capture.writeAudio(data, frames);
    if (!audioEnabled) return frames;
    // O step agora troca o Audio no meio do jogo (latência mínima, timing ou refresh novos): núcleos que mandam
    // áudio de uma thread própria não podem escrever num Audio que acabou de ser destruído. A escrita não bloqueia.
    std::lock_guard<std::mutex> lock(audioLock);
    if (audio) {
        audio->write(data, frames);
    }
    return frames;
}

int16_t LibretroDroid::handleSetInputState(
    unsigned int port,
    unsigned int device,
    unsigned int index,
    unsigned int id
) {
    if (netplay) {
        return netplay->getInputState(port, device, index, id);
    }
    // Núcleos com thread própria (Dolphin, PPSSPP) leem a entrada fora do retro_run, enquanto o pause() solta o
    // Input e os eventos de tecla o alteram.
    std::lock_guard<std::mutex> lock(inputLock);
    if (input) {
        return input->getInputState(port, device, index, id);
    }
    return 0;
}

void LibretroDroid::startNetplay(int fd, unsigned localPort, unsigned delayFrames, unsigned epoch) {
    std::lock_guard<std::mutex> lock(coreLock);
    netplay = std::make_unique<Netplay>(fd, localPort, delayFrames, (uint8_t) epoch);
    netplayState = 0;
    netplayFrameCount = 0;
}

void LibretroDroid::stopNetplay() {
    std::lock_guard<std::mutex> lock(coreLock);
    netplay = nullptr;
    netplayState = -1;
}

int64_t LibretroDroid::netplayStatus() const {
    return netplayState.load();
}

uint32_t LibretroDroid::netplayFrame() const {
    return netplayFrameCount.load();
}

uintptr_t LibretroDroid::handleGetCurrentFrameBuffer() {
    if (video) {
        return video->getCurrentFramebuffer();
    }
    return 0;
}

void LibretroDroid::reset() {
    std::lock_guard<std::mutex> lock(coreLock);

    if (!core) return;

    core->retro_reset();
}

std::vector<int8_t> LibretroDroid::serializeState() {
    std::lock_guard<std::mutex> lock(coreLock);

    if (!core) return {};

    size_t size = core->retro_serialize_size();
    if (size == 0) {
        return {};
    }

    std::vector<int8_t> data(size);
    // Se o núcleo recusar, devolve vazio para o Kotlin nunca gravar um estado com lixo.
    if (!core->retro_serialize(data.data(), size)) {
        LOGE("retro_serialize failed");
        return {};
    }

    return data;
}

void LibretroDroid::resetCheat() {
    std::lock_guard<std::mutex> lock(coreLock);

    if (!core) return;

    core->retro_cheat_reset();
    cheatCodes.clear();
}

void LibretroDroid::setCheat(unsigned index, bool enabled, const std::string& code) {
    std::lock_guard<std::mutex> lock(coreLock);

    if (!core) return;

    // Alguns núcleos guardam o ponteiro em vez de copiar o texto: ele vive até o próximo resetCheat (antes
    // cada chamada vazava uma cópia).
    cheatCodes.push_back(code);
    core->retro_cheat_set(index, enabled, cheatCodes.back().c_str());
}

bool LibretroDroid::isVideoBackendLost() const {
    return Environment::getInstance().isUseVulkan() && VulkanContext::getInstance().isLost();
}

bool LibretroDroid::requiresVideoRefresh() const {
    return dirtyVideo;
}

void LibretroDroid::clearRequiresVideoRefresh() {
    dirtyVideo = false;
}

void LibretroDroid::afterGameLoad() {
    struct retro_system_av_info system_av_info {};
    core->retro_get_system_av_info(&system_av_info);

    contentFps = system_av_info.timing.fps > 0 ? system_av_info.timing.fps : 60.0;
    // Uma mudança de tela anunciada antes do carregamento vale desde o primeiro quadro.
    float pendingRate = pendingScreenRefreshRate.exchange(0.0F);
    if (pendingRate > 0) {
        screenRefreshRate = pendingRate;
        Environment::getInstance().setTargetRefreshRate(pendingRate);
    }
    replaceFpsSync(std::make_unique<FPSSync>(contentFps, screenRefreshRate));
    contentSampleRate = system_av_info.timing.sample_rate;
    runCount = 0;
    videoFrameCount = 0;
    // Um SET_SYSTEM_AV_INFO durante o carregamento já está refletido no get_system_av_info acima.
    Environment::getInstance().clearAvTimingUpdated();

    double inputSampleRate = system_av_info.timing.sample_rate * fpsSync->getTimeStretchFactor();
    audioSampleRate = (int) std::lround(inputSampleRate);

    // O núcleo costuma pedir a latência mínima durante o retro_load_game, antes de chegarmos aqui. Um pedido
    // feito depois (dentro do retro_run) é tratado no fim do step, que recria o áudio.
    appliedMinimumLatencyMs = Environment::getInstance().getMinimumAudioLatency();
    replaceAudio(std::make_unique<Audio>(
        (int32_t) std::lround(inputSampleRate),
        system_av_info.timing.fps,
        preferLowLatencyAudio,
        appliedMinimumLatencyMs
    ));

    publishRuntimeHints(frameSpeed.load());

    defaultAspectRatio = findDefaultAspectRatio(system_av_info);
}

float LibretroDroid::findDefaultAspectRatio(const retro_system_av_info& system_av_info) {
    // libretro: an aspect ratio <= 0 means "use base_width / base_height". Only < 0 was handled, so cores
    // reporting 0 (Gearsystem, Fuse, Ardens...) got a zero-width picture: a black screen.
    float result = system_av_info.geometry.aspect_ratio;
    if (!(result > 0) && system_av_info.geometry.base_height > 0) {
        result =
            (float) system_av_info.geometry.base_width / (float) system_av_info.geometry.base_height;
    }
    return result > 0 ? result : 4.0F / 3.0F;
}

void LibretroDroid::handleRumbleUpdates(const std::function<void(int, float, float)> &handler) {
    if (rumble && rumbleEnabled) {
        rumble->handleRumbleUpdates(handler);
    }
}

void LibretroDroid::setViewport(Rect viewportRect) {
    // Roda pela fila da thread GL, que atende eventos mesmo pausada, quando o destroy() pode estar rodando.
    std::lock_guard<std::mutex> lock(coreLock);
    this->viewportRect = viewportRect;

    if (video != nullptr) {
        video->updateViewportSize(viewportRect);
    }
}

} //namespace libretrodroid
