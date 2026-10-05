/*
 *     Copyright (C) 2020  Filippo Scognamiglio
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

#ifndef LIBRETRODROID_ENVIRONMENT_H
#define LIBRETRODROID_ENVIRONMENT_H

#define MODULE_NAME_CORE "Libretro Core"

#include <vector>
#include <string>
#include <cstring>
#include <cmath>
#include <EGL/egl.h>
#include <unordered_map>
#include <array>
#include <atomic>

#include "../../libretro-common/include/libretro.h"
#include "log.h"
#include "rumblestate.h"

class Environment {
public:
    static Environment& getInstance()
    {
        static Environment instance;
        return instance;
    }
    Environment(Environment const&) = delete;
    void operator=(Environment const&) = delete;

    static void callback_retro_log(enum retro_log_level level, const char *fmt, ...);

    static bool callback_set_rumble_state(
        unsigned port,
        enum retro_rumble_effect effect,
        uint16_t strength
    );

    static bool callback_environment(unsigned cmd, void *data);

    void setEnableVirtualFileSystem(bool value);
    void setEnableMicrophone(bool value);

private:
    Environment() {}

public:
    void initialize(
        const std::string &requiredSystemDirectory,
        const std::string &requiredSavesDirectory,
        retro_hw_get_current_framebuffer_t required_callback_get_current_framebuffer
    );

    void deinitialize();

    void updateVariable(const std::string &key, const std::string &value);

    void setLanguage(const std::string &androidLanguage);

    float retrieveGameSpecificAspectRatio();

    bool handle_callback_set_rumble_state(
        unsigned port,
        enum retro_rumble_effect effect,
        uint16_t strength
    );

    bool handle_callback_environment(unsigned cmd, void *data);

    retro_hw_context_reset_t getHwContextReset() const;
    retro_hw_context_reset_t getHwContextDestroy() const;

    struct retro_disk_control_callback* getRetroDiskControlCallback() const;

    int getPixelFormat() const;
    bool isUseHwAcceleration() const;
    bool isUseDepth() const;
    bool isHwContextRejected() const;
    void setRelaxedGlesVersion(bool relaxed);

    /** O núcleo pode pedir um contexto Vulkan (ponte por AHardwareBuffer); recusado quando falso. */
    static void setAllowVulkan(bool allow);
    bool isUseVulkan() const;
    /** O núcleo recebeu o contexto de vídeo que pediu (GLES ou Vulkan). */
    bool isHwContextAccepted() const;
    const retro_frame_time_callback& getFrameTimeCallback() const { return frameTimeCallback; }

    /** Medidor da fila de áudio pedido pelo núcleo (callback nulo se não pediu). */
    const retro_audio_buffer_status_callback& getAudioBufferStatusCallback() const { return audioBufferStatusCallback; }
    /** Latência mínima de áudio pedida pelo núcleo, em ms (0 = a padrão). */
    unsigned getMinimumAudioLatency() const { return minimumAudioLatencyMs; }

    /** Taxa de atualização da tela (GET_TARGET_REFRESH_RATE). */
    void setTargetRefreshRate(float rate) { targetRefreshRate = rate; }
    /** Estado que o LibretroDroid publica antes de cada retro_run (GET_FASTFORWARDING, GET_THROTTLE_STATE). */
    void setFastForwarding(bool value) { fastForwarding = value; }
    void setThrottleState(unsigned mode, float rate) { throttleMode = mode; throttleRate = rate; }
    /** Falso nos quadros intermediários do avanço rápido (GET_AUDIO_VIDEO_ENABLE, bit de vídeo). */
    void setVideoEnabled(bool value) { videoEnabled = value; }
    bool isVideoEnabled() const { return videoEnabled; }

    /** SET_SYSTEM_AV_INFO trouxe um timing novo (fps e taxa de amostragem); lido pela thread de emulação. */
    bool isAvTimingUpdated() const { return avTimingUpdated; }
    void clearAvTimingUpdated() { avTimingUpdated = false; }
    double getAvTimingFps() const { return avTimingFps; }
    double getAvTimingSampleRate() const { return avTimingSampleRate; }
    bool isUseStencil() const;
    bool isBottomLeftOrigin() const;

    float getScreenRotation() const;
    bool isScreenRotationUpdated() const;
    void clearScreenRotationUpdated();

    unsigned int getGameGeometryWidth() const;
    unsigned int getGameGeometryHeight() const;
    unsigned int getGameGeometryMaxWidth() const;
    unsigned int getGameGeometryMaxHeight() const;
    float getGameGeometryAspectRatio() const;
    bool isGameGeometryUpdated() const;
    void clearGameGeometryUpdated();

    std::array<libretrodroid::RumbleState, 4> & getLastRumbleStates();

    const std::vector<struct Variable> getVariables() const;

    const std::vector<std::vector<struct Controller>> &getControllers() const;

private:
    bool environment_handle_set_variables(const struct retro_variable* received);
    bool environment_handle_get_variable(struct retro_variable* requested);
    bool environment_handle_set_controller_info(const struct retro_controller_info* received);
    bool environment_handle_set_hw_render(struct retro_hw_render_callback* hw_render_callback);
    bool environment_handle_set_hw_render_vulkan(struct retro_hw_render_callback* hw_render_callback);
    bool environment_handle_get_vfs_interface(struct retro_vfs_interface_info* vfs_interface_info);
    bool environment_handle_get_microphone_interface(struct retro_microphone_interface* microphone_interface);

private:
    retro_hw_context_reset_t hw_context_reset = nullptr;
    retro_hw_context_reset_t hw_context_destroy = nullptr;
    struct retro_disk_control_callback *retro_disk_control_callback = nullptr;

    std::string savesDirectory;
    std::string systemDirectory;
    retro_hw_get_current_framebuffer_t callback_get_current_framebuffer = nullptr;
    unsigned language = RETRO_LANGUAGE_ENGLISH;
    bool useVirtualFileSystem = false;
    bool enableMicrophone = false;

    int pixelFormat = RETRO_PIXEL_FORMAT_RGB565;
    bool useHWAcceleration = false;
    bool useDepth = false;
    bool hwContextRejected = false;
    bool useVulkan = false;
    bool relaxedGlesVersion = false;
    retro_frame_time_callback frameTimeCallback {};
    retro_audio_buffer_status_callback audioBufferStatusCallback {};
    unsigned minimumAudioLatencyMs = 0;
    // Lidos dentro do retro_run, que alguns núcleos (Dolphin, PPSSPP) rodam em threads próprias.
    std::atomic<float> targetRefreshRate {60.0f};
    std::atomic<bool> fastForwarding {false};
    std::atomic<unsigned> throttleMode {RETRO_THROTTLE_NONE};
    std::atomic<float> throttleRate {60.0f};
    std::atomic<bool> videoEnabled {true};
    std::atomic<bool> avTimingUpdated {false};
    std::atomic<double> avTimingFps {0.0};
    std::atomic<double> avTimingSampleRate {0.0};
    bool useStencil = false;
    bool bottomLeftOrigin = false;

    float screenRotation = 0;
    bool screenRotationUpdated = false;

    bool gameGeometryUpdated = false;
    unsigned gameGeometryWidth = 0;
    unsigned gameGeometryHeight = 0;
    unsigned gameGeometryMaxWidth = 0;
    unsigned gameGeometryMaxHeight = 0;
    float gameGeometryAspectRatio = -1.0f;

    std::array<libretrodroid::RumbleState, 4> rumbleStates;

    std::unordered_map<std::string, struct Variable> variables;
    bool dirtyVariables = false;

    std::vector<std::vector<struct Controller>> controllers;
};

struct Variable {
public:
    std::string key;
    std::string value;
    std::string description;
};

struct Controller {
public:
    unsigned id;
    std::string description;
};

#endif //LIBRETRODROID_ENVIRONMENT_H

