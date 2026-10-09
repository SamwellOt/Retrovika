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

#define MODULE_NAME_CORE "Libretro Core"

#include "vulkan/vulkancontext.h"
#include <algorithm>
#include <utility>
#include <vector>
#include <string>
#include <cstring>
#include <cmath>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <unordered_map>
#include <chrono>

#include "../../libretro-common/include/libretro.h"
#include "log.h"
#include "environment.h"
#include "vfs/vfs.h"
#include "microphone/microphoneinterface.h"

void Environment::initialize(
    const std::string &requiredSystemDirectory,
    const std::string &requiredSavesDirectory,
    retro_hw_get_current_framebuffer_t required_callback_get_current_framebuffer
) {
    callback_get_current_framebuffer = required_callback_get_current_framebuffer;
    systemDirectory = requiredSystemDirectory;
    savesDirectory = requiredSavesDirectory;
}

void Environment::deinitialize() {
    callback_get_current_framebuffer = nullptr;
    hw_context_reset = nullptr;
    hw_context_destroy = nullptr;

    retro_disk_control_callback = nullptr;

    savesDirectory = std::string();
    systemDirectory = std::string();
    language = RETRO_LANGUAGE_ENGLISH;

    pixelFormat = RETRO_PIXEL_FORMAT_RGB565;
    useHWAcceleration = false;
    hwContextRejected = false;
    useVulkan = false;
    relaxedGlesVersion = false;
    frameTimeCallback = {};
    memoryRegions.clear();
    audioBufferStatusCallback = {};
    minimumAudioLatencyMs = 0;
    targetRefreshRate = 60.0f;
    fastForwarding = false;
    throttleMode = RETRO_THROTTLE_NONE;
    throttleRate = 60.0f;
    videoEnabled = true;
    avTimingUpdated = false;
    avTimingFps = 0.0;
    avTimingSampleRate = 0.0;
    useDepth = false;
    useStencil = false;
    bottomLeftOrigin = false;
    screenRotation = 0;

    gameGeometryUpdated = false;
    gameGeometryWidth = 0;
    gameGeometryHeight = 0;
    gameGeometryMaxWidth = 0;
    gameGeometryMaxHeight = 0;
    gameGeometryAspectRatio = -1.0f;

    rumbleStates.fill(libretrodroid::RumbleState {});

    // O Environment é global: sem isso as opções e os controles do núcleo anterior chegavam ao próximo
    // (o GET_VARIABLE respondia chaves que ele nunca declarou).
    variables.clear();
    dirtyVariables = false;
    controllers.clear();
}

void Environment::updateVariable(const std::string& key, const std::string& value) {
    auto current = variables[key];
    current.key = key;

    if (value != current.value) {
        current.value = value;
        variables[key] = current;
        dirtyVariables = true;
    }
}

bool Environment::environment_handle_set_variables(const struct retro_variable* received) {
    if (received == nullptr) {
        return true;
    }

    unsigned count = 0;
    while (received[count].key != nullptr) {
        // Some cores (LRPS2) send entries without a value: skip them instead of crashing in strlen.
        if (received[count].value == nullptr) {
            count++;
            continue;
        }
        LOGD("Received variable %s: %s", received[count].key, received[count].value);
        registerVariable(received[count].key, received[count].value);
        count++;
    }

    return true;
}

// Guarda as regiões de memória que o núcleo descreve: as que o jogo pode mudar e que não são memória de vídeo. Serve à
// busca na memória e à tradução dentro do jogo (a EWRAM do GBA, por exemplo, não é a RAM do sistema).
bool Environment::environment_handle_set_memory_maps(const struct retro_memory_map* received) {
    memoryRegions.clear();
    if (received == nullptr || received->descriptors == nullptr) return true;
    for (unsigned i = 0; i < received->num_descriptors; i++) {
        const auto& d = received->descriptors[i];
        if (d.ptr == nullptr || d.len == 0) continue;
        if (d.flags & (RETRO_MEMDESC_CONST | RETRO_MEMDESC_VIDEO_RAM)) continue;
        auto* base = static_cast<uint8_t*>(d.ptr) + d.offset;
        // Espelhos do mesmo trecho vêm como descritores com o mesmo ponteiro.
        bool duplicate = false;
        for (const auto& r : memoryRegions) {
            if (base < r.data + r.length && base + d.len > r.data) { duplicate = true; break; }
        }
        if (duplicate) continue;
        LOGI("Memory map: region start=0x%zx length=%zu flags=0x%llx", d.start, d.len, (unsigned long long) d.flags);
        memoryRegions.push_back({base, d.len, d.flags, d.start});
    }
    return true;
}

// "Description; first|second|...": the first value is the default.
void Environment::registerVariable(const std::string& key, const std::string& description) {
    std::string value(description);

    auto separator = value.find(';');
    auto valuesStart = separator == std::string::npos ? 0 : value.find_first_not_of(' ', separator + 1);
    if (valuesStart == std::string::npos) valuesStart = value.size();
    std::vector<std::string> allowed;
    for (size_t start = valuesStart; start <= value.size();) {
        auto end = value.find('|', start);
        if (end == std::string::npos) end = value.size();
        allowed.push_back(value.substr(start, end - start));
        start = end + 1;
    }
    value = allowed.empty() ? std::string() : allowed.front();

    auto currentVariable = variables[key];
    currentVariable.key = key;
    currentVariable.description = description;

    // A value the core does not offer (a wrong preset, or a saved choice from an older version of
    // the core) would reach it as-is: cores then keep an undefined setting. Fall back to the default.
    bool known = std::find(allowed.begin(), allowed.end(), currentVariable.value) != allowed.end();
    if (currentVariable.value.empty() || !known) {
        if (!currentVariable.value.empty()) {
            LOGW("Value %s is not valid for %s: using %s", currentVariable.value.c_str(), key.c_str(), value.c_str());
        }
        currentVariable.value = value;
    }

    variables[key] = currentVariable;
    LOGD("Assigning variable %s: %s", currentVariable.key.c_str(), currentVariable.value.c_str());
}

// Core options v1/v2 carry the values as an array; they are folded into the legacy
// "Description; default|other" form so that a single path feeds the pause menu and the saved choices.
void Environment::registerOption(
    const char* key,
    const char* desc,
    const struct retro_core_option_value* values,
    const char* defaultValue
) {
    if (key == nullptr || values == nullptr) return;

    std::string text(desc != nullptr ? desc : key);
    // The legacy form splits at the first ';': a free-text description may contain one ("Foo (a; b)").
    std::replace(text.begin(), text.end(), ';', ',');
    text += "; ";

    // The declared default goes first; without a valid one, the first value stays the default.
    bool hasDefault = false;
    if (defaultValue != nullptr) {
        for (unsigned i = 0; i < RETRO_NUM_CORE_OPTION_VALUES_MAX && values[i].value != nullptr; i++) {
            if (strcmp(values[i].value, defaultValue) == 0) { hasDefault = true; break; }
        }
    }

    // A value with '|' cannot be told apart from two values in the legacy form: it is left out.
    auto usable = [](const char* value) { return strchr(value, '|') == nullptr; };
    std::string joined;
    if (hasDefault && usable(defaultValue)) joined += defaultValue;
    else hasDefault = false;
    for (unsigned i = 0; i < RETRO_NUM_CORE_OPTION_VALUES_MAX && values[i].value != nullptr; i++) {
        if (!usable(values[i].value)) continue;
        if (hasDefault && strcmp(values[i].value, defaultValue) == 0) continue;
        if (!joined.empty()) joined += "|";
        joined += values[i].value;
    }
    if (joined.empty()) return;

    registerVariable(key, text + joined);
}

bool Environment::environment_handle_set_core_options(const struct retro_core_option_definition* received) {
    if (received == nullptr) return true;
    for (unsigned i = 0; received[i].key != nullptr; i++) {
        registerOption(received[i].key, received[i].desc, received[i].values, received[i].default_value);
    }
    return true;
}

bool Environment::environment_handle_set_core_options_v2(const struct retro_core_options_v2* received) {
    if (received == nullptr || received->definitions == nullptr) return true;
    for (unsigned i = 0; received->definitions[i].key != nullptr; i++) {
        const auto& definition = received->definitions[i];
        registerOption(definition.key, definition.desc, definition.values, definition.default_value);
    }
    return true;
}

bool Environment::environment_handle_get_variable(struct retro_variable* requested) {
    LOGD("Variable requested %s", requested->key);
    auto foundVariable = variables.find(std::string(requested->key));

    if (foundVariable == variables.end()) {
        return false;
    }

    requested->value = foundVariable->second.value.c_str();
    return true;
}

bool Environment::environment_handle_set_controller_info(const struct retro_controller_info* received) {
    controllers.clear();

    unsigned player = 0;
    while (received[player].types != nullptr) {

        auto currentPlayer = received[player];

        controllers.emplace_back();

        unsigned controller = 0;
        while (controller < currentPlayer.num_types && currentPlayer.types[controller].desc != nullptr) {
            auto currentController = currentPlayer.types[controller];
            LOGD("Received controller for player %d: %d %s", player, currentController.id, currentController.desc);

            controllers[player].push_back(Controller { currentController.id, currentController.desc });
            controller++;
        }

        player++;
    }

    return true;
}

namespace {
// Definido antes de o núcleo carregar (o Environment é reiniciado a cada create): vale por view.
bool allowVulkanFlag = false;
}

void Environment::setAllowVulkan(bool allow) {
    allowVulkanFlag = allow;
}

bool Environment::isUseVulkan() const {
    return useVulkan;
}

bool Environment::isHwContextAccepted() const {
    return useHWAcceleration || useVulkan;
}

bool Environment::environment_handle_set_hw_render_vulkan(struct retro_hw_render_callback* hw_render_callback) {
    // A ponte Vulkan > GLES precisa de GLES 3 (buffers de hardware importados como EGLImage, fences do GL)
    // e de um aparelho que a sustente. Sem isso o pedido é recusado e o núcleo cai para outro renderizador.
    GLint major = 0;
    glGetIntegerv(GL_MAJOR_VERSION, &major);
    if (!allowVulkanFlag || major < 3 || !libretrodroid::VulkanContext::isAvailable()) {
        LOGE("Vulkan context refused (allowed: %d, GLES %d)", allowVulkanFlag, major);
        hwContextRejected = true;
        return false;
    }

    useVulkan = true;
    useHWAcceleration = false;
    useDepth = false;
    useStencil = false;
    bottomLeftOrigin = false;
    hw_context_reset = hw_render_callback->context_reset;
    hw_context_destroy = hw_render_callback->context_destroy;
    return true;
}

bool Environment::environment_handle_set_hw_render(struct retro_hw_render_callback* hw_render_callback) {
    if (hw_render_callback->context_type == RETRO_HW_CONTEXT_VULKAN) {
        return environment_handle_set_hw_render_vulkan(hw_render_callback);
    }

    // Only GLES contexts can be provided. Accepting Vulkan or desktop GL makes the core believe
    // it has a context it will never get: returning false lets it fall back to GLES.
    switch (hw_render_callback->context_type) {
        case RETRO_HW_CONTEXT_OPENGLES2:
        case RETRO_HW_CONTEXT_OPENGLES3:
        case RETRO_HW_CONTEXT_OPENGLES_VERSION:
            break;
        default:
            LOGE("Unsupported hardware context requested: %d", hw_render_callback->context_type);
            hwContextRejected = true;
            return false;
    }

    // The game is loaded on the GL thread with our context current: a core asking for a newer GLES
    // (Citra wants 3.2) would otherwise abort compiling its shaders instead of failing to load.
    if (hw_render_callback->context_type != RETRO_HW_CONTEXT_OPENGLES2) {
        unsigned requiredMajor = hw_render_callback->context_type == RETRO_HW_CONTEXT_OPENGLES3 && hw_render_callback->version_major == 0
            ? 3 : hw_render_callback->version_major;
        unsigned requiredMinor = hw_render_callback->version_major == 0 ? 0 : hw_render_callback->version_minor;
        GLint major = 0, minor = 0;
        glGetIntegerv(GL_MAJOR_VERSION, &major);
        glGetIntegerv(GL_MINOR_VERSION, &minor);
        // Núcleos marcados (o Play! pede 3.2 mas roda em 3.1) passam mesmo com o contexto abaixo do pedido.
        if (!relaxedGlesVersion && major > 0 && (unsigned) (major * 100 + minor) < requiredMajor * 100 + requiredMinor) {
            LOGE("Core requires OpenGL ES %u.%u, context is %d.%d", requiredMajor, requiredMinor, major, minor);
            hwContextRejected = true;
            return false;
        }
    }

    useHWAcceleration = true;
    useDepth = hw_render_callback->depth;
    useStencil = hw_render_callback->stencil;
    bottomLeftOrigin = hw_render_callback->bottom_left_origin;

    hw_context_destroy = hw_render_callback->context_destroy;
    hw_context_reset = hw_render_callback->context_reset;
    hw_render_callback->get_current_framebuffer = callback_get_current_framebuffer;
    hw_render_callback->get_proc_address = &eglGetProcAddress;

    return true;
}

bool Environment::environment_handle_get_vfs_interface(struct retro_vfs_interface_info* vfsInterfaceInfo) {
    // Always offered, like RetroArch does: some cores (Stella) only recognise the game through the
    // VFS stat. Paths that are not virtual files go straight to the file system.
    if (vfsInterfaceInfo->required_interface_version > libretrodroid::VFS::SUPPORTED_VERSION) {
        return false;
    }

    vfsInterfaceInfo->required_interface_version = libretrodroid::VFS::SUPPORTED_VERSION;
    vfsInterfaceInfo->iface = libretrodroid::VFS::getInterface();
    return true;
}

bool Environment::environment_handle_get_microphone_interface(struct retro_microphone_interface* microphone_interface) {
    if (!enableMicrophone) {
        return false;
    }

    *microphone_interface = *libretrodroid::MicrophoneInterface::getInterface();
    return true;
}

namespace {

retro_time_t perfGetTimeUsec() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

retro_perf_tick_t perfGetCounter() {
    return (retro_perf_tick_t) std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

// Só o que a ABI garante, sem olhar o CPU: um núcleo que achasse AVX e executasse a instrução em um aparelho
// sem ela cairia com SIGILL. No x86_64 do Android o piso é SSE até SSSE3 (SSE4 e POPCNT não entram, por cautela).
uint64_t perfGetCpuFeatures() {
    uint64_t features = 0;
#if defined(__aarch64__)
    features |= RETRO_SIMD_NEON | RETRO_SIMD_ASIMD;
#elif defined(__ARM_NEON)
    features |= RETRO_SIMD_NEON;
#endif
#if defined(__x86_64__)
    features |= RETRO_SIMD_MMX | RETRO_SIMD_SSE | RETRO_SIMD_SSE2 | RETRO_SIMD_SSE3 | RETRO_SIMD_SSSE3;
#elif defined(__i386__)
    features |= RETRO_SIMD_MMX | RETRO_SIMD_SSE | RETRO_SIMD_SSE2;
#endif
    return features;
}

// Os contadores de desempenho do núcleo não são usados: registrar, medir e listar não fazem nada.
void perfRegister(struct retro_perf_counter* counter) {
    if (counter != nullptr) counter->registered = true;
}

void perfStartStop(struct retro_perf_counter*) {}

void perfLog() {}

} // namespace

void Environment::callback_retro_log(enum retro_log_level level, const char *fmt, ...) {
    va_list argptr;
    va_start(argptr, fmt);

    switch (level) {
#if VERBOSE_LOGGING
        case RETRO_LOG_DEBUG:
            __android_log_vprint(ANDROID_LOG_DEBUG, MODULE_NAME_CORE, fmt, argptr);
            break;
#endif
        case RETRO_LOG_INFO:
            __android_log_vprint(ANDROID_LOG_INFO, MODULE_NAME_CORE, fmt, argptr);
            break;
        case RETRO_LOG_WARN:
            __android_log_vprint(ANDROID_LOG_WARN, MODULE_NAME_CORE, fmt, argptr);
            break;
        case RETRO_LOG_ERROR:
            __android_log_vprint(ANDROID_LOG_ERROR, MODULE_NAME_CORE, fmt, argptr);
            break;
        default:
            // Log nothing in here.
            break;
    }
}

bool Environment::callback_set_rumble_state(unsigned port, enum retro_rumble_effect effect, uint16_t strength) {
    return Environment::getInstance().handle_callback_set_rumble_state(port, effect, strength);
}

bool Environment::handle_callback_set_rumble_state(unsigned port, enum retro_rumble_effect effect, uint16_t strength) {
    LOGV("Setting rumble strength for port %i to %i", port, strength);
    if (port < 0 || port > 3) return false;

    if (effect == RETRO_RUMBLE_STRONG) {
        rumbleStates[port].strengthStrong = strength;
    } else if (effect == RETRO_RUMBLE_WEAK) {
        rumbleStates[port].strengthWeak = strength;
    }

    return true;
}

bool Environment::callback_environment(unsigned cmd, void *data) {
    return Environment::getInstance().handle_callback_environment(cmd, data);
}

bool Environment::handle_callback_environment(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *((bool*) data) = true;
            return true;

        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            LOGD("Called SET_PIXEL_FORMAT");
            pixelFormat = *static_cast<enum retro_pixel_format *>(data);
            return true;
        }

        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
            LOGD("Called SET_INPUT_DESCRIPTORS");
            // Only labels for the frontend's UI, but some cores (Ardens) refuse to load if it fails.
            return true;

        case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK: {
            LOGD("Called SET_FRAME_TIME_CALLBACK");
            // TIC-80 refuses to load without it.
            auto* callback = static_cast<const struct retro_frame_time_callback*>(data);
            frameTimeCallback = callback != nullptr ? *callback : retro_frame_time_callback {};
            return true;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE:
            LOGD("Called RETRO_ENVIRONMENT_GET_VARIABLE");
            return environment_handle_get_variable(static_cast<struct retro_variable*>(data));

        case RETRO_ENVIRONMENT_SET_VARIABLES:
            LOGD("Called RETRO_ENVIRONMENT_SET_VARIABLES");
            return environment_handle_set_variables(static_cast<const struct retro_variable*>(data));

        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            LOGD("Called RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION");
            *((unsigned*) data) = 2;
            return true;

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
            LOGD("Called RETRO_ENVIRONMENT_SET_CORE_OPTIONS");
            return environment_handle_set_core_options(static_cast<const struct retro_core_option_definition*>(data));

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: {
            LOGD("Called RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL");
            // Only the English table ("us"): the pause menu is localized by the app, not by the core.
            auto* intl = static_cast<const struct retro_core_options_intl*>(data);
            return environment_handle_set_core_options(intl != nullptr ? intl->us : nullptr);
        }

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
            LOGD("Called RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2");
            return environment_handle_set_core_options_v2(static_cast<const struct retro_core_options_v2*>(data));

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
            LOGD("Called RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL");
            auto* intl = static_cast<const struct retro_core_options_v2_intl*>(data);
            return environment_handle_set_core_options_v2(intl != nullptr ? intl->us : nullptr);
        }

        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
            LOGD("Called RETRO_ENVIRONMENT_SET_MEMORY_MAPS");
            return environment_handle_set_memory_maps(static_cast<const struct retro_memory_map*>(data));

        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            LOGD("Called RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE. Is dirty?: %d", dirtyVariables);
            *((bool*) data) = dirtyVariables;
            dirtyVariables = false;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: {
            LOGD("Called RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER");
            *((unsigned*) data) = retro_hw_context_type::RETRO_HW_CONTEXT_OPENGLES3;
            return true;
        }

        case RETRO_ENVIRONMENT_SET_HW_RENDER:
            LOGD("Called RETRO_ENVIRONMENT_SET_HW_RENDER");
            return environment_handle_set_hw_render(static_cast<struct retro_hw_render_callback*>(data));

        case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: {
            LOGD("Called SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE");
            auto* negotiation = static_cast<const struct retro_hw_render_context_negotiation_interface*>(data);
            if (negotiation == nullptr || !allowVulkanFlag ||
                negotiation->interface_type != RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN) {
                return false;
            }
            libretrodroid::VulkanContext::getInstance().setNegotiation(
                static_cast<const struct retro_hw_render_context_negotiation_interface_vulkan*>(data)
            );
            return true;
        }

        case RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT: {
            LOGD("Called GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT");
            auto* support = static_cast<struct retro_hw_render_context_negotiation_interface*>(data);
            // Versão 0: o tipo não é suportado (a chamada continua sendo atendida, como manda a API).
            support->interface_version = support->interface_type == RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN && allowVulkanFlag
                ? RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN_VERSION : 0;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE: {
            LOGD("Called GET_HW_RENDER_INTERFACE");
            if (!useVulkan) return false;
            auto* vulkanInterface = libretrodroid::VulkanContext::getInstance().renderInterface();
            if (vulkanInterface == nullptr) return false;
            *static_cast<const struct retro_hw_render_interface**>(data) = reinterpret_cast<const struct retro_hw_render_interface*>(vulkanInterface);
            return true;
        }

        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            // Input::getInputState responde RETRO_DEVICE_ID_JOYPAD_MASK.
            LOGD("Called RETRO_ENVIRONMENT_GET_INPUT_BITMASKS");
            return true;

        case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
            LOGD("Called RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE");
            ((struct retro_rumble_interface*) data)->set_rumble_state = &callback_set_rumble_state;
            return true;

        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            LOGD("Called RETRO_ENVIRONMENT_GET_LOG_INTERFACE");
            ((struct retro_log_callback*) data)->log = &callback_retro_log;
            return true;

        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            LOGD("Called RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY");
            *(const char**) data = savesDirectory.c_str();
            return !savesDirectory.empty();

        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            LOGD("Called RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY");
            *(const char**) data = systemDirectory.c_str();
            return !systemDirectory.empty();

        case RETRO_ENVIRONMENT_SET_ROTATION: {
            LOGD("Called RETRO_ENVIRONMENT_SET_ROTATION");
            unsigned screenRotationIndex = (*static_cast<unsigned*>(data));
            screenRotation = screenRotationIndex * (float) (-M_PI / 2.0);
            screenRotationUpdated = true;
            return true;
        }

        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE: {
            LOGD("Called RETRO_ENVIRONMENT_SET_ROTATION");
            retro_disk_control_callback = static_cast<struct retro_disk_control_callback*>(data);
            return true;
        }

        case RETRO_ENVIRONMENT_GET_PERF_INTERFACE: {
            LOGD("Called RETRO_ENVIRONMENT_GET_PERF_INTERFACE");
            // Alguns núcleos chamam os ponteiros sem conferir, então todos precisam existir.
            static const struct retro_perf_callback perfCallback {
                &perfGetTimeUsec,
                &perfGetCpuFeatures,
                &perfGetCounter,
                &perfRegister,
                &perfStartStop,
                &perfStartStop,
                &perfLog
            };
            if (data == nullptr) return false;
            *static_cast<struct retro_perf_callback*>(data) = perfCallback;
            return true;
        }

        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            struct retro_game_geometry *geometry = static_cast<struct retro_game_geometry *>(data);
            if (cmd == RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO) {
                // O timing (fps e taxa de amostragem) fica a cargo do LibretroDroid::step, que recria o
                // FPSSync e o áudio na thread de emulação.
                auto* avInfo = static_cast<struct retro_system_av_info *>(data);
                avTimingFps = avInfo->timing.fps;
                avTimingSampleRate = avInfo->timing.sample_rate;
                avTimingUpdated = true;
            }
            gameGeometryHeight = geometry->base_height;
            gameGeometryWidth = geometry->base_width;
            // max_* is only meaningful in SET_SYSTEM_AV_INFO: SET_GEOMETRY must not change it.
            if (cmd == RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO) {
                gameGeometryMaxWidth = geometry->max_width;
                gameGeometryMaxHeight = geometry->max_height;
            }
            gameGeometryAspectRatio = geometry->aspect_ratio;
            gameGeometryUpdated = true;
            return true;
        }

        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
            LOGD("Called RETRO_ENVIRONMENT_SET_CONTROLLER_INFO");
            return environment_handle_set_controller_info(static_cast<const struct retro_controller_info*>(data));

        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            LOGD("Called RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE");
            // Bit 0 vídeo, bit 1 áudio. O áudio fica sempre ligado (a captura da transmissão e o mudo dependem
            // dele); o vídeo só cai nos quadros intermediários do avanço rápido.
            if (data != nullptr) {
                *static_cast<int*>(data) = (videoEnabled ? 1 : 0) | 2;
            }
            return true;

        case RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK: {
            LOGD("Called RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK");
            // Nulo (a estrutura ou o callback) desliga o medidor.
            auto* callback = static_cast<const struct retro_audio_buffer_status_callback*>(data);
            audioBufferStatusCallback = callback != nullptr ? *callback : retro_audio_buffer_status_callback {};
            return true;
        }

        case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY: {
            LOGD("Called RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY");
            if (data == nullptr) return false;
            // A API manda atender até 512 ms. Quem aplica é o LibretroDroid: na criação do Audio (pedido feito
            // no retro_load_game) ou, se vier dentro do retro_run, recriando o Audio no fim do step.
            minimumAudioLatencyMs = std::min(*static_cast<const unsigned*>(data), 512u);
            return true;
        }

        // Como no RetroArch: um núcleo que só sonda o suporte passa NULL, e isso não pode derrubar o processo.
        case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
            if (data == nullptr) return false;
            *static_cast<bool*>(data) = fastForwarding;
            return true;

        case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE:
            if (data == nullptr) return false;
            *static_cast<float*>(data) = targetRefreshRate;
            return true;

        case RETRO_ENVIRONMENT_GET_THROTTLE_STATE: {
            if (data == nullptr) return false;
            auto* state = static_cast<struct retro_throttle_state*>(data);
            state->mode = throttleMode;
            state->rate = throttleRate;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            LOGD("Called RETRO_ENVIRONMENT_GET_LANGUAGE");
            *((unsigned*) data) = language;
            return true;

        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            LOGD("Called RETRO_ENVIRONMENT_GET_VFS_INTERFACE");
            return environment_handle_get_vfs_interface(static_cast<struct retro_vfs_interface_info*>(data));

        case RETRO_ENVIRONMENT_GET_MICROPHONE_INTERFACE:
            LOGD("Called RETRO_ENVIRONMENT_GET_MICROPHONE_INTERFACE");
            return environment_handle_get_microphone_interface(static_cast<struct retro_microphone_interface*>(data));

        default:
            LOGD("callback environment has been called: %u", cmd);
            return false;
    }
}

void Environment::setLanguage(const std::string& androidLanguage) {
    std::unordered_map<std::string, unsigned> languages {
            { "en", RETRO_LANGUAGE_ENGLISH },
            { "ja", RETRO_LANGUAGE_JAPANESE },
            { "fr", RETRO_LANGUAGE_FRENCH },
            { "es", RETRO_LANGUAGE_SPANISH },
            { "de", RETRO_LANGUAGE_GERMAN },
            { "it", RETRO_LANGUAGE_ITALIAN },
            { "nl", RETRO_LANGUAGE_DUTCH },
            { "pt", RETRO_LANGUAGE_PORTUGUESE_PORTUGAL },
            { "ru", RETRO_LANGUAGE_RUSSIAN },
            { "ko", RETRO_LANGUAGE_KOREAN },
            { "zh", RETRO_LANGUAGE_CHINESE_TRADITIONAL },
            { "eo", RETRO_LANGUAGE_ESPERANTO },
            { "pl", RETRO_LANGUAGE_POLISH },
            { "vi", RETRO_LANGUAGE_VIETNAMESE },
            { "ar", RETRO_LANGUAGE_ARABIC },
            { "el", RETRO_LANGUAGE_GREEK },
            { "tr", RETRO_LANGUAGE_TURKISH },
            { "sv", RETRO_LANGUAGE_SWEDISH },
            { "fi", RETRO_LANGUAGE_FINNISH },
            { "cs", RETRO_LANGUAGE_CZECH },
            { "uk", RETRO_LANGUAGE_UKRAINIAN },
            { "hu", RETRO_LANGUAGE_HUNGARIAN },
            { "he", RETRO_LANGUAGE_HEBREW },
            { "iw", RETRO_LANGUAGE_HEBREW },
            { "id", RETRO_LANGUAGE_INDONESIAN },
            { "in", RETRO_LANGUAGE_INDONESIAN },
            { "fa", RETRO_LANGUAGE_PERSIAN },
            { "sk", RETRO_LANGUAGE_SLOVAK },
            { "ca", RETRO_LANGUAGE_CATALAN },
            { "be", RETRO_LANGUAGE_BELARUSIAN }
    };

    if (languages.find(androidLanguage) != languages.end()) {
        language = languages[androidLanguage];
    }
}

retro_hw_context_reset_t Environment::getHwContextReset() const {
    return hw_context_reset;
}

retro_hw_context_reset_t Environment::getHwContextDestroy() const {
    return hw_context_destroy;
}

struct retro_disk_control_callback* Environment::getRetroDiskControlCallback() const {
    return retro_disk_control_callback;
}

int Environment::getPixelFormat() const {
    return pixelFormat;
}

bool Environment::isUseHwAcceleration() const {
    return useHWAcceleration;
}

bool Environment::isHwContextRejected() const {
    return hwContextRejected;
}

void Environment::setRelaxedGlesVersion(bool relaxed) {
    relaxedGlesVersion = relaxed;
}

bool Environment::isUseDepth() const {
    return useDepth;
}

bool Environment::isUseStencil() const {
    return useStencil;
}

bool Environment::isBottomLeftOrigin() const {
    return bottomLeftOrigin;
}

float Environment::getScreenRotation() const {
    return screenRotation;
}

bool Environment::isGameGeometryUpdated() const {
    return gameGeometryUpdated;
}

void Environment::clearGameGeometryUpdated() {
    gameGeometryUpdated = false;
}

unsigned int Environment::getGameGeometryWidth() const {
    return gameGeometryWidth;
}

unsigned int Environment::getGameGeometryHeight() const {
    return gameGeometryHeight;
}

unsigned int Environment::getGameGeometryMaxWidth() const {
    return gameGeometryMaxWidth;
}

unsigned int Environment::getGameGeometryMaxHeight() const {
    return gameGeometryMaxHeight;
}

float Environment::getGameGeometryAspectRatio() const {
    return gameGeometryAspectRatio;
}

const std::vector<struct Variable> Environment::getVariables() const {
    std::vector<struct Variable> result;

    std::for_each(
        variables.begin(),
        variables.end(),
        [&](std::pair<std::string, struct Variable> item) {
            result.push_back(item.second);
        }
    );

    std::sort(
        result.begin(),
        result.end(),
        [](struct Variable v1, struct Variable v2) {
            return v1.key < v2.key;
        }
    );

    return result;
}

const std::vector<std::vector<struct Controller>> &Environment::getControllers() const {
    return controllers;
}

float Environment::retrieveGameSpecificAspectRatio() {
    if (getGameGeometryAspectRatio() > 0) {
        return getGameGeometryAspectRatio();
    }

    if (getGameGeometryWidth() > 0 && getGameGeometryHeight() > 0) {
        return (float) getGameGeometryWidth() / (float) getGameGeometryHeight();
    }

    return -1.0f;
}

bool Environment::isScreenRotationUpdated() const {
    return screenRotationUpdated;
}

void Environment::clearScreenRotationUpdated() {
    screenRotationUpdated = false;
}

std::array<libretrodroid::RumbleState, 4>& Environment::getLastRumbleStates() {
    return rumbleStates;
}

void Environment::setEnableVirtualFileSystem(bool value) {
    this->useVirtualFileSystem = value;
}

void Environment::setEnableMicrophone(bool value) {
    this->enableMicrophone = value;
}
