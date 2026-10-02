#include "vulkancontext.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <string>

#include "../log.h"

namespace libretrodroid {

namespace {

constexpr const char* EXT_AHB = "VK_ANDROID_external_memory_android_hardware_buffer";
constexpr const char* EXT_FOREIGN = "VK_EXT_queue_family_foreign";
constexpr uint64_t SECOND_NS = 1000ull * 1000ull * 1000ull;

PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC pEglGetNativeClientBuffer = nullptr;
PFNEGLCREATEIMAGEKHRPROC pEglCreateImage = nullptr;
PFNEGLDESTROYIMAGEKHRPROC pEglDestroyImage = nullptr;
PFNGLEGLIMAGETARGETTEXTURE2DOESPROC pGlImageTargetTexture2D = nullptr;

bool containsToken(const char* list, const char* token) {
    if (list == nullptr) return false;
    size_t length = strlen(token);
    for (const char* p = list; (p = strstr(p, token)) != nullptr; p += length) {
        bool startOk = p == list || p[-1] == ' ';
        bool endOk = p[length] == ' ' || p[length] == '\0';
        if (startOk && endOk) return true;
    }
    return false;
}

bool loadEglFunctions() {
    if (pGlImageTargetTexture2D != nullptr) return true;
    pEglGetNativeClientBuffer = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC) eglGetProcAddress("eglGetNativeClientBufferANDROID");
    pEglCreateImage = (PFNEGLCREATEIMAGEKHRPROC) eglGetProcAddress("eglCreateImageKHR");
    pEglDestroyImage = (PFNEGLDESTROYIMAGEKHRPROC) eglGetProcAddress("eglDestroyImageKHR");
    pGlImageTargetTexture2D = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC) eglGetProcAddress("glEGLImageTargetTexture2DOES");
    return pEglGetNativeClientBuffer && pEglCreateImage && pEglDestroyImage && pGlImageTargetTexture2D;
}

/** O loader entende Vulkan 1.1? (Sem isso as extensões de memória externa não são do núcleo da API.) */
bool loaderSupportsVulkan11() {
    auto enumerateVersion = (PFN_vkEnumerateInstanceVersion) vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
    if (enumerateVersion == nullptr) return false;
    uint32_t version = 0;
    return enumerateVersion(&version) == VK_SUCCESS && version >= VK_API_VERSION_1_1;
}

bool hasDeviceExtensions(VkPhysicalDevice gpu) {
    uint32_t count = 0;
    vkEnumerateDeviceExtensionProperties(gpu, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> props(count);
    vkEnumerateDeviceExtensionProperties(gpu, nullptr, &count, props.data());
    bool ahb = false, foreign = false;
    for (auto& p : props) {
        ahb |= strcmp(p.extensionName, EXT_AHB) == 0;
        foreign |= strcmp(p.extensionName, EXT_FOREIGN) == 0;
    }
    return ahb && foreign;
}

VkInstance createInstanceWithApp(const VkInstanceCreateInfo* requested) {
    VkApplicationInfo app = {};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "Retrovika";
    app.pEngineName = "LibretroDroid";
    app.apiVersion = VK_API_VERSION_1_1;
    if (requested->pApplicationInfo != nullptr) {
        app = *requested->pApplicationInfo;
        if (app.apiVersion < VK_API_VERSION_1_1) app.apiVersion = VK_API_VERSION_1_1;
    }
    VkInstanceCreateInfo info = *requested;
    info.pApplicationInfo = &app;
    VkInstance result = VK_NULL_HANDLE;
    if (vkCreateInstance(&info, nullptr, &result) != VK_SUCCESS) {
        return VK_NULL_HANDLE;
    }
    return result;
}

/** O núcleo cria o dispositivo por aqui: o frontend soma as extensões que a ponte com o GL precisa. */
VkDevice createDeviceWithBridgeExtensions(VkPhysicalDevice gpu, void* opaque, const VkDeviceCreateInfo* requested) {
    std::vector<const char*> names;
    for (uint32_t i = 0; i < requested->enabledExtensionCount; i++) names.push_back(requested->ppEnabledExtensionNames[i]);
    for (const char* required : { EXT_AHB, EXT_FOREIGN }) {
        bool present = std::any_of(names.begin(), names.end(), [&](const char* n) { return strcmp(n, required) == 0; });
        if (!present) names.push_back(required);
    }
    VkDeviceCreateInfo info = *requested;
    info.enabledExtensionCount = (uint32_t) names.size();
    info.ppEnabledExtensionNames = names.data();
    VkDevice result = VK_NULL_HANDLE;
    if (vkCreateDevice(gpu, &info, nullptr, &result) != VK_SUCCESS) {
        return VK_NULL_HANDLE;
    }
    return result;
}

VkInstance createInstanceForCore(void*, const VkInstanceCreateInfo* requested) {
    return createInstanceWithApp(requested);
}

}

VulkanContext& VulkanContext::getInstance() {
    static VulkanContext instance;
    return instance;
}

bool VulkanContext::isAvailable() {
    // Atômico: o app testa de uma thread própria (probeWithOwnContext) e o núcleo pergunta na thread GL.
    static std::atomic<int> cached{-1};
    if (cached.load() >= 0) return cached.load() == 1;

    // Sem contexto EGL não dá para saber (um núcleo que pede o contexto no retro_init, fora da thread GL):
    // responde que não, mas sem guardar, para o pedido seguinte, já com contexto, ser avaliado de verdade.
    EGLDisplay display = eglGetCurrentDisplay();
    if (display == EGL_NO_DISPLAY || eglGetCurrentContext() == EGL_NO_CONTEXT) return false;

    auto unavailable = [&]() { cached.store(0); return false; };
    const char* eglExtensions = eglQueryString(display, EGL_EXTENSIONS);
    if (!containsToken(eglExtensions, "EGL_ANDROID_get_native_client_buffer") ||
        !containsToken(eglExtensions, "EGL_ANDROID_image_native_buffer") ||
        !containsToken(eglExtensions, "EGL_KHR_image_base")) {
        LOGW("Vulkan bridge unavailable: the EGL implementation cannot import hardware buffers");
        return unavailable();
    }
    if (!containsToken((const char*) glGetString(GL_EXTENSIONS), "GL_OES_EGL_image") || !loadEglFunctions()) {
        LOGW("Vulkan bridge unavailable: GL_OES_EGL_image is missing");
        return unavailable();
    }
    if (!loaderSupportsVulkan11()) {
        LOGW("Vulkan bridge unavailable: the Vulkan loader is older than 1.1");
        return unavailable();
    }

    // Autoteste completo com um contexto descartável (instância, dispositivo e um buffer compartilhado de ponta a
    // ponta, Vulkan > AHardwareBuffer > EGLImage): se algo falhar, o pedido de Vulkan é recusado aqui, antes de o
    // núcleo se comprometer (um núcleo que recebe o contexto e perde a criação dele depois nem sempre se descarrega
    // sem abortar o processo).
    VulkanContext candidate;
    bool works = candidate.createInstance() && candidate.createDevice();
    if (works) {
        Slot slot;
        works = candidate.createSlot(slot, 64, 64);
        candidate.destroySlot(slot);
    }
    candidate.destroy();
    if (!works) {
        LOGW("Vulkan bridge unavailable: the self-test failed on this device");
        return unavailable();
    }

    cached.store(1);
    return true;
}

bool VulkanContext::probeWithOwnContext() {
    // Quem chamou pode ter um contexto atual nesta thread: ele volta a ser o atual no fim.
    EGLDisplay previousDisplay = eglGetCurrentDisplay();
    EGLSurface previousDraw = eglGetCurrentSurface(EGL_DRAW);
    EGLSurface previousRead = eglGetCurrentSurface(EGL_READ);
    EGLContext previousContext = eglGetCurrentContext();

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) return false;

    const EGLint configAttributes[] = { EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE };
    EGLConfig config = nullptr;
    EGLint configs = 0;
    if (!eglChooseConfig(display, configAttributes, &config, 1, &configs) || configs < 1) return false;

    const EGLint contextAttributes[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    const EGLint surfaceAttributes[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
    EGLSurface surface = eglCreatePbufferSurface(display, config, surfaceAttributes);

    bool result = false;
    if (context != EGL_NO_CONTEXT && surface != EGL_NO_SURFACE && eglMakeCurrent(display, surface, surface, context)) {
        result = isAvailable();
        if (previousContext != EGL_NO_CONTEXT && previousDisplay != EGL_NO_DISPLAY) {
            eglMakeCurrent(previousDisplay, previousDraw, previousRead, previousContext);
        } else {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
    }
    if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
    if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
    return result;
}

void VulkanContext::setNegotiation(const retro_hw_render_context_negotiation_interface_vulkan* received) {
    if (received == nullptr) {
        hasNegotiation = false;
        return;
    }
    negotiation = {};
    negotiation.interface_type = received->interface_type;
    negotiation.interface_version = received->interface_version;
    negotiation.get_application_info = received->get_application_info;
    negotiation.create_device = received->create_device;
    negotiation.destroy_device = received->destroy_device;
    if (received->interface_version >= 2) {
        negotiation.create_instance = received->create_instance;
        negotiation.create_device2 = received->create_device2;
    }
    hasNegotiation = true;
}

bool VulkanContext::createInstance() {
    getInstanceProcAddr = vkGetInstanceProcAddr;

    if (hasNegotiation && negotiation.interface_version >= 2 && negotiation.create_instance != nullptr) {
        VkApplicationInfo app = {};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.pApplicationName = "Retrovika";
        app.pEngineName = "LibretroDroid";
        app.apiVersion = VK_API_VERSION_1_1;
        if (negotiation.get_application_info != nullptr) {
            if (const VkApplicationInfo* requested = negotiation.get_application_info()) app = *requested;
        }
        if (app.apiVersion < VK_API_VERSION_1_1) app.apiVersion = VK_API_VERSION_1_1;
        instance = negotiation.create_instance(getInstanceProcAddr, &app, &createInstanceForCore, nullptr);
        if (instance != VK_NULL_HANDLE) return true;
        LOGW("The core could not create the Vulkan instance: using the default one");
    }

    VkApplicationInfo app = {};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "Retrovika";
    app.pEngineName = "LibretroDroid";
    app.apiVersion = VK_API_VERSION_1_1;
    if (hasNegotiation && negotiation.get_application_info != nullptr) {
        if (const VkApplicationInfo* requested = negotiation.get_application_info()) app = *requested;
        if (app.apiVersion < VK_API_VERSION_1_1) app.apiVersion = VK_API_VERSION_1_1;
    }
    VkInstanceCreateInfo info = { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
    info.pApplicationInfo = &app;
    VkResult result = vkCreateInstance(&info, nullptr, &instance);
    if (result != VK_SUCCESS) {
        LOGE("vkCreateInstance failed: %d", result);
        instance = VK_NULL_HANDLE;
        return false;
    }
    return true;
}

bool VulkanContext::pickPhysicalDevice(VkPhysicalDevice* chosen) {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance, &count, nullptr);
    std::vector<VkPhysicalDevice> gpus(count);
    vkEnumeratePhysicalDevices(instance, &count, gpus.data());
    for (VkPhysicalDevice candidate : gpus) {
        if (!hasDeviceExtensions(candidate)) continue;
        uint32_t families = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &families, nullptr);
        std::vector<VkQueueFamilyProperties> props(families);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &families, props.data());
        for (auto& family : props) {
            if ((family.queueFlags & VK_QUEUE_GRAPHICS_BIT) && (family.queueFlags & VK_QUEUE_COMPUTE_BIT)) {
                *chosen = candidate;
                return true;
            }
        }
    }
    return false;
}

bool VulkanContext::createDeviceDefault(VkPhysicalDevice chosen) {
    uint32_t families = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(chosen, &families, nullptr);
    std::vector<VkQueueFamilyProperties> props(families);
    vkGetPhysicalDeviceQueueFamilyProperties(chosen, &families, props.data());
    int family = -1;
    for (uint32_t i = 0; i < families; i++) {
        if ((props[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && (props[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) {
            family = (int) i;
            break;
        }
    }
    if (family < 0) return false;

    float priority = 1.0F;
    VkDeviceQueueCreateInfo queueInfo = { VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO };
    queueInfo.queueFamilyIndex = (uint32_t) family;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    const char* extensions[] = { EXT_AHB, EXT_FOREIGN };
    VkDeviceCreateInfo info = { VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO };
    info.queueCreateInfoCount = 1;
    info.pQueueCreateInfos = &queueInfo;
    info.enabledExtensionCount = 2;
    info.ppEnabledExtensionNames = extensions;

    VkResult result = vkCreateDevice(chosen, &info, nullptr, &device);
    if (result != VK_SUCCESS) {
        LOGE("vkCreateDevice failed: %d", result);
        device = VK_NULL_HANDLE;
        return false;
    }
    gpu = chosen;
    queueFamily = (uint32_t) family;
    vkGetDeviceQueue(device, queueFamily, 0, &queue);
    return true;
}

bool VulkanContext::createDevice() {
    VkPhysicalDevice chosen = VK_NULL_HANDLE;
    if (!pickPhysicalDevice(&chosen)) {
        LOGE("No Vulkan device can share hardware buffers with GL");
        return false;
    }

    bool createdByCore = false;
    if (hasNegotiation) {
        retro_vulkan_context context = {};
        if (negotiation.interface_version >= 2 && negotiation.create_device2 != nullptr) {
            createdByCore = negotiation.create_device2(
                &context, instance, chosen, VK_NULL_HANDLE, getInstanceProcAddr, &createDeviceWithBridgeExtensions, nullptr
            );
        } else if (negotiation.create_device != nullptr) {
            const char* extensions[] = { EXT_AHB, EXT_FOREIGN };
            VkPhysicalDeviceFeatures features = {};
            createdByCore = negotiation.create_device(
                &context, instance, chosen, VK_NULL_HANDLE, getInstanceProcAddr, extensions, 2, nullptr, 0, &features
            );
        }
        if (createdByCore && context.device == VK_NULL_HANDLE) {
            // Disse que criou, mas não entregou o dispositivo: não há o que o núcleo soltar depois.
            LOGW("The core reported a Vulkan device but returned none");
            createdByCore = false;
        }
        if (createdByCore) {
            gpu = context.gpu != VK_NULL_HANDLE ? context.gpu : chosen;
            device = context.device;
            queue = context.queue;
            queueFamily = context.queue_family_index;
            deviceCreatedByCore = true;
            if (queue == VK_NULL_HANDLE) {
                vkGetDeviceQueue(device, queueFamily, 0, &queue);
            }
            if (queue == VK_NULL_HANDLE) {
                // O dispositivo é do núcleo (o destroy() chama o destroy_device dele): só falha.
                LOGE("The Vulkan device created by the core has no queue");
                return false;
            }
        } else {
            LOGW("The core could not create the Vulkan device: using the default one");
        }
    }

    if (!createdByCore && !createDeviceDefault(chosen)) {
        return false;
    }

    getDeviceProcAddr = (PFN_vkGetDeviceProcAddr) getInstanceProcAddr(instance, "vkGetDeviceProcAddr");
    getBufferProperties = getDeviceProcAddr == nullptr ? nullptr
        : (PFN_vkGetAndroidHardwareBufferPropertiesANDROID) getDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID");
    if (getBufferProperties == nullptr) {
        LOGE("The Vulkan device does not expose hardware buffer sharing");
        return false;
    }
    return true;
}

bool VulkanContext::createFrameResources() {
    VkCommandPoolCreateInfo poolInfo = { VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamily;
    if (vkCreateCommandPool(device, &poolInfo, nullptr, &commandPool) != VK_SUCCESS) return false;

    VkCommandBufferAllocateInfo allocInfo = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
    allocInfo.commandPool = commandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = SYNC_IMAGES;
    if (vkAllocateCommandBuffers(device, &allocInfo, commandBuffers.data()) != VK_SUCCESS) return false;

    for (uint32_t i = 0; i < SYNC_IMAGES; i++) {
        VkFenceCreateInfo fenceInfo = { VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
        if (vkCreateFence(device, &fenceInfo, nullptr, &fences[i]) != VK_SUCCESS) return false;
        fenceSubmitted[i] = false;
    }
    return true;
}

void VulkanContext::fillInterface() {
    iface = {};
    iface.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
    iface.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
    iface.handle = this;
    iface.instance = instance;
    iface.gpu = gpu;
    iface.device = device;
    iface.get_device_proc_addr = getDeviceProcAddr;
    iface.get_instance_proc_addr = getInstanceProcAddr;
    iface.queue = queue;
    iface.queue_index = queueFamily;
    iface.set_image = &VulkanContext::setImage;
    iface.get_sync_index = &VulkanContext::getSyncIndex;
    iface.get_sync_index_mask = &VulkanContext::getSyncIndexMask;
    iface.set_command_buffers = &VulkanContext::setCommandBuffers;
    iface.wait_sync_index = &VulkanContext::waitSyncIndex;
    iface.lock_queue = &VulkanContext::lockQueue;
    iface.unlock_queue = &VulkanContext::unlockQueue;
    iface.set_signal_semaphore = &VulkanContext::setSignalSemaphore;
}

bool VulkanContext::create() {
    if (ready) return true;
    if (broken) return false;

    // Na falha nada é desfeito aqui: o núcleo já recebeu parte do contexto, e o destroy() do LibretroDroid roda o
    // context_destroy dele primeiro e só então chama o nosso destroy(), que libera o que existir.
    if (!createInstance() || !createDevice() || !createFrameResources()) {
        LOGE("Cannot create the Vulkan context");
        broken = true;
        return false;
    }
    // Se o aparelho não consegue compartilhar um buffer entre Vulkan e GL, é melhor saber agora (o carregamento
    // falha e o app volta ao outro renderizador) do que mostrar tela preta para sempre depois do primeiro quadro.
    Slot probe;
    bool probeOk = createSlot(probe, 64, 64);
    destroySlot(probe);
    if (!probeOk) {
        LOGE("The Vulkan to GL frame bridge does not work on this device");
        broken = true;
        return false;
    }

    fillInterface();
    ready = true;
    LOGI("Vulkan context ready (device created by %s)", deviceCreatedByCore ? "the core" : "the frontend");
    return true;
}

void VulkanContext::destroy() {
    if (device != VK_NULL_HANDLE) {
        {
            std::lock_guard<std::mutex> lock(queueMutex);
            vkDeviceWaitIdle(device);
        }
        releaseFrames();
        for (auto& fence : fences) {
            if (fence != VK_NULL_HANDLE) vkDestroyFence(device, fence, nullptr);
            fence = VK_NULL_HANDLE;
        }
        if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, nullptr);
        commandPool = VK_NULL_HANDLE;
        commandBuffers = {};
    }

    // O núcleo solta o que criou além do dispositivo antes de o dispositivo e a instância irem embora.
    if (deviceCreatedByCore && hasNegotiation && negotiation.destroy_device != nullptr) {
        negotiation.destroy_device();
    }
    if (device != VK_NULL_HANDLE) vkDestroyDevice(device, nullptr);
    if (instance != VK_NULL_HANDLE) vkDestroyInstance(instance, nullptr);

    device = VK_NULL_HANDLE;
    instance = VK_NULL_HANDLE;
    gpu = VK_NULL_HANDLE;
    queue = VK_NULL_HANDLE;
    deviceCreatedByCore = false;
    ready = false;
    broken = false;
    pending = {};
    coreCommandBuffers.clear();
    signalSemaphore = VK_NULL_HANDLE;
    syncIndex = 0;
    iface = {};
    hasNegotiation = false;
}

// region Interface para o núcleo

void VulkanContext::setImage(void* handle, const retro_vulkan_image* image, uint32_t numSemaphores, const VkSemaphore* semaphores, uint32_t srcQueueFamily) {
    auto* self = static_cast<VulkanContext*>(handle);
    self->pending.valid = image != nullptr;
    if (image == nullptr) return;
    self->pending.image = image->create_info.image;
    self->pending.layout = image->image_layout;
    self->pending.range = image->create_info.subresourceRange;
    self->pending.srcQueueFamily = srcQueueFamily;
    self->pending.semaphores.assign(semaphores, semaphores + (semaphores != nullptr ? numSemaphores : 0));
}

uint32_t VulkanContext::getSyncIndex(void* handle) {
    return static_cast<VulkanContext*>(handle)->syncIndex;
}

uint32_t VulkanContext::getSyncIndexMask(void*) {
    return (1u << SYNC_IMAGES) - 1;
}

void VulkanContext::setCommandBuffers(void* handle, uint32_t num, const VkCommandBuffer* cmd) {
    auto* self = static_cast<VulkanContext*>(handle);
    self->coreCommandBuffers.assign(cmd, cmd + (cmd != nullptr ? num : 0));
}

void VulkanContext::waitSyncIndex(void* handle) {
    auto* self = static_cast<VulkanContext*>(handle);
    self->waitSync(self->syncIndex, 5 * SECOND_NS);
}

void VulkanContext::lockQueue(void* handle) {
    static_cast<VulkanContext*>(handle)->queueMutex.lock();
}

void VulkanContext::unlockQueue(void* handle) {
    static_cast<VulkanContext*>(handle)->queueMutex.unlock();
}

void VulkanContext::setSignalSemaphore(void* handle, VkSemaphore semaphore) {
    static_cast<VulkanContext*>(handle)->signalSemaphore = semaphore;
}

// endregion

// region Quadros compartilhados

bool VulkanContext::waitSync(uint32_t sync, uint64_t timeoutNs) {
    // wait_sync_index pode vir de uma thread do núcleo, enquanto a de emulação envia o quadro.
    std::lock_guard<std::mutex> lock(fenceMutex);
    if (!fenceSubmitted[sync]) return true;
    VkResult result = vkWaitForFences(device, 1, &fences[sync], VK_TRUE, timeoutNs);
    if (result != VK_SUCCESS) {
        LOGE("Waiting for the Vulkan frame failed: %d", result);
        return false;
    }
    vkResetFences(device, 1, &fences[sync]);
    fenceSubmitted[sync] = false;
    return true;
}

int VulkanContext::pickFreeSlot() const {
    for (int i = 0; i < SLOTS; i++) {
        if (i == shown) continue;
        bool busy = std::any_of(inFlight.begin(), inFlight.end(), [i](const InFlight& f) { return f.slot == i; });
        if (!busy) return i;
    }
    return -1;
}

bool VulkanContext::createSlot(Slot& slot, unsigned width, unsigned height) {
    AHardwareBuffer_Desc desc = {};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    if (AHardwareBuffer_allocate(&desc, &slot.buffer) != 0) {
        LOGE("Cannot allocate a %ux%u hardware buffer", width, height);
        return false;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID format = { VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID };
    VkAndroidHardwareBufferPropertiesANDROID properties = { VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID };
    properties.pNext = &format;
    if (getBufferProperties(device, slot.buffer, &properties) != VK_SUCCESS || format.format == VK_FORMAT_UNDEFINED) {
        LOGE("The hardware buffer has no Vulkan format");
        return false;
    }

    VkExternalMemoryImageCreateInfo external = { VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO };
    external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo imageInfo = { VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
    imageInfo.pNext = &external;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = format.format;
    imageInfo.extent = { width, height, 1 };
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(device, &imageInfo, nullptr, &slot.image) != VK_SUCCESS) {
        LOGE("vkCreateImage for the hardware buffer failed");
        return false;
    }

    uint32_t memoryType = 0;
    while (memoryType < 32 && !(properties.memoryTypeBits & (1u << memoryType))) memoryType++;
    if (memoryType == 32) return false;

    VkMemoryDedicatedAllocateInfo dedicated = { VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO };
    dedicated.image = slot.image;
    VkImportAndroidHardwareBufferInfoANDROID import = { VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID };
    import.pNext = &dedicated;
    import.buffer = slot.buffer;
    VkMemoryAllocateInfo allocate = { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
    allocate.pNext = &import;
    allocate.allocationSize = properties.allocationSize;
    allocate.memoryTypeIndex = memoryType;
    if (vkAllocateMemory(device, &allocate, nullptr, &slot.memory) != VK_SUCCESS ||
        vkBindImageMemory(device, slot.image, slot.memory, 0) != VK_SUCCESS) {
        LOGE("Cannot import the hardware buffer into Vulkan");
        return false;
    }

    EGLClientBuffer clientBuffer = pEglGetNativeClientBuffer(slot.buffer);
    const EGLint attributes[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    slot.display = eglGetCurrentDisplay();
    slot.glContext = eglGetCurrentContext();
    slot.eglImage = pEglCreateImage(slot.display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attributes);
    if (slot.eglImage == EGL_NO_IMAGE_KHR) {
        LOGE("Cannot import the hardware buffer into EGL");
        return false;
    }
    glGenTextures(1, &slot.texture);
    glBindTexture(GL_TEXTURE_2D, slot.texture);
    pGlImageTargetTexture2D(GL_TEXTURE_2D, (GLeglImageOES) slot.eglImage);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);
    return true;
}

void VulkanContext::destroySlot(Slot& slot) {
    // Só no contexto que os criou: num contexto EGL novo os mesmos números podem ser objetos de outro dono.
    // Sem ele (destroy() na thread principal) a textura e a fence vão embora junto com o contexto.
    bool glAvailable = slot.glContext != EGL_NO_CONTEXT && eglGetCurrentContext() == slot.glContext;
    if (glAvailable) {
        if (slot.glFence != nullptr) glDeleteSync(slot.glFence);
        if (slot.texture != 0) glDeleteTextures(1, &slot.texture);
    }
    if (slot.eglImage != EGL_NO_IMAGE_KHR && pEglDestroyImage != nullptr) {
        EGLDisplay display = slot.display != EGL_NO_DISPLAY ? slot.display : eglGetDisplay(EGL_DEFAULT_DISPLAY);
        pEglDestroyImage(display, slot.eglImage);
    }
    if (slot.image != VK_NULL_HANDLE) vkDestroyImage(device, slot.image, nullptr);
    if (slot.memory != VK_NULL_HANDLE) vkFreeMemory(device, slot.memory, nullptr);
    if (slot.buffer != nullptr) AHardwareBuffer_release(slot.buffer);
    slot = Slot {};
}

void VulkanContext::releaseFrames() {
    if (device == VK_NULL_HANDLE) return;
    for (uint32_t i = 0; i < SYNC_IMAGES; i++) waitSync(i, SECOND_NS);
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        vkQueueWaitIdle(queue);
    }
    for (auto& slot : slots) destroySlot(slot);
    inFlight.clear();
    shown = -1;
    lastHandedOut = -1;
    slotWidth = slotHeight = 0;
}

bool VulkanContext::ensureSlots(unsigned width, unsigned height) {
    if (slotWidth == width && slotHeight == height && slots[0].buffer != nullptr) return true;

    releaseFrames();
    for (auto& slot : slots) {
        if (!createSlot(slot, width, height)) {
            for (auto& s : slots) destroySlot(s);
            return false;
        }
    }
    slotWidth = width;
    slotHeight = height;
    return true;
}

void VulkanContext::recordCopy(VkCommandBuffer cmd, const Pending& frame, VkImage dst, unsigned width, unsigned height) {
    VkCommandBufferBeginInfo begin = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &begin);

    bool general = frame.layout == VK_IMAGE_LAYOUT_GENERAL;
    VkImageLayout copyLayout = general ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    bool transfer = frame.srcQueueFamily != VK_QUEUE_FAMILY_IGNORED && frame.srcQueueFamily != queueFamily;

    VkImageMemoryBarrier before[2] = {};
    before[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    before[0].srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    before[0].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    before[0].oldLayout = frame.layout;
    before[0].newLayout = copyLayout;
    before[0].srcQueueFamilyIndex = transfer ? frame.srcQueueFamily : VK_QUEUE_FAMILY_IGNORED;
    before[0].dstQueueFamilyIndex = transfer ? queueFamily : VK_QUEUE_FAMILY_IGNORED;
    before[0].image = frame.image;
    before[0].subresourceRange = frame.range;

    // O destino é sobrescrito inteiro: o conteúdo antigo não importa (e vem de fora do Vulkan).
    before[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    before[1].srcAccessMask = 0;
    before[1].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    before[1].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    before[1].newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    before[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    before[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    before[1].image = dst;
    before[1].subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 2, before);

    VkImageBlit region = {};
    region.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, frame.range.baseMipLevel, frame.range.baseArrayLayer, 1 };
    region.srcOffsets[1] = { (int32_t) width, (int32_t) height, 1 };
    region.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    region.dstOffsets[1] = { (int32_t) width, (int32_t) height, 1 };
    vkCmdBlitImage(cmd, frame.image, copyLayout, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region, VK_FILTER_NEAREST);

    VkImageMemoryBarrier after[2] = {};
    uint32_t count = 0;
    if (!general || transfer) {
        after[count].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        after[count].srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        after[count].dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        after[count].oldLayout = copyLayout;
        after[count].newLayout = frame.layout;
        // O frontend devolve o dono: a próxima vez que o núcleo usar a imagem ele a adquire de novo.
        after[count].srcQueueFamilyIndex = transfer ? queueFamily : VK_QUEUE_FAMILY_IGNORED;
        after[count].dstQueueFamilyIndex = transfer ? frame.srcQueueFamily : VK_QUEUE_FAMILY_IGNORED;
        after[count].image = frame.image;
        after[count].subresourceRange = frame.range;
        count++;
    }
    // Entrega o buffer ao GL (uma API fora do Vulkan): a posse vai para a "fila estrangeira".
    after[count].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    after[count].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    after[count].dstAccessMask = 0;
    after[count].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    after[count].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    after[count].srcQueueFamilyIndex = queueFamily;
    after[count].dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    after[count].image = dst;
    after[count].subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
    count++;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, count, after);

    vkEndCommandBuffer(cmd);
}

bool VulkanContext::present(unsigned width, unsigned height, GLuint* texture) {
    if (!ready || broken || !pending.valid || width == 0 || height == 0) return false;
    // Um set_image vale para um video_refresh só: o seguinte sem imagem nova é quadro repetido.
    Pending frame = pending;
    pending.valid = false;
    std::vector<VkCommandBuffer> coreCommands;
    coreCommands.swap(coreCommandBuffers);
    VkSemaphore signal = signalSemaphore;
    signalSemaphore = VK_NULL_HANDLE;

    // Daqui em diante, desistir do quadro ainda envia o que o núcleo entregou: os command buffers dele precisam
    // rodar, os semáforos de espera precisam ser consumidos e o de sinal precisa ser sinalizado, senão o núcleo
    // fica esperando por um trabalho que nunca vai acontecer.
    auto dropFrame = [&]() {
        submitWithoutCopy(frame, coreCommands, signal);
        return false;
    };

    if (!ensureSlots(width, height)) {
        broken = true;
        return dropFrame();
    }

    // O GL já desenhou o quadro entregue da última vez: marca onde, para só reescrever o buffer depois.
    if (lastHandedOut >= 0 && slots[lastHandedOut].glFence == nullptr) {
        slots[lastHandedOut].glFence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }
    lastHandedOut = -1;

    // No máximo dois quadros adiantados da GPU: passando disso, espera o mais antigo (e ele vira o visível).
    while (inFlight.size() >= 2) {
        InFlight oldest = inFlight.front();
        if (!waitSync(oldest.sync, 5 * SECOND_NS)) { broken = true; return dropFrame(); }
        shown = oldest.slot;
        inFlight.pop_front();
    }

    uint32_t sync = syncIndex;
    if (!waitSync(sync, 5 * SECOND_NS)) { broken = true; return dropFrame(); }
    int slotIndex = pickFreeSlot();
    if (slotIndex < 0) return dropFrame();
    Slot& slot = slots[slotIndex];
    if (slot.glFence != nullptr) {
        glClientWaitSync(slot.glFence, GL_SYNC_FLUSH_COMMANDS_BIT, 50ull * 1000 * 1000);
        glDeleteSync(slot.glFence);
        slot.glFence = nullptr;
    }

    VkCommandBuffer cmd = commandBuffers[sync];
    vkResetCommandBuffer(cmd, 0);
    recordCopy(cmd, frame, slot.image, width, height);

    std::vector<VkCommandBuffer> submitted = coreCommands;
    submitted.push_back(cmd);
    // Com os command buffers do núcleo no mesmo envio, os semáforos dele não valem (a interface manda ignorar).
    bool useSemaphores = coreCommands.empty() && !frame.semaphores.empty();
    std::vector<VkPipelineStageFlags> waitStages(frame.semaphores.size(), VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkSubmitInfo submit = { VK_STRUCTURE_TYPE_SUBMIT_INFO };
    submit.waitSemaphoreCount = useSemaphores ? (uint32_t) frame.semaphores.size() : 0;
    submit.pWaitSemaphores = useSemaphores ? frame.semaphores.data() : nullptr;
    submit.pWaitDstStageMask = useSemaphores ? waitStages.data() : nullptr;
    submit.commandBufferCount = (uint32_t) submitted.size();
    submit.pCommandBuffers = submitted.data();
    submit.signalSemaphoreCount = signal != VK_NULL_HANDLE ? 1 : 0;
    submit.pSignalSemaphores = signal != VK_NULL_HANDLE ? &signal : nullptr;

    VkResult result;
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        result = vkQueueSubmit(queue, 1, &submit, fences[sync]);
    }
    if (result != VK_SUCCESS) {
        LOGE("vkQueueSubmit failed: %d", result);
        broken = true;
        return false;
    }
    {
        // waitSync lê o mesmo flag com este lock, possivelmente de uma thread do núcleo.
        std::lock_guard<std::mutex> lock(fenceMutex);
        fenceSubmitted[sync] = true;
    }
    inFlight.push_back({ slotIndex, sync });
    syncIndex = (sync + 1) % SYNC_IMAGES;

    // Mostra o quadro mais novo que a GPU já terminou; se nenhum terminou ainda, espera o mais antigo.
    while (!inFlight.empty() && vkGetFenceStatus(device, fences[inFlight.front().sync]) == VK_SUCCESS) {
        shown = inFlight.front().slot;
        inFlight.pop_front();
    }
    if (shown < 0) {
        InFlight oldest = inFlight.front();
        if (!waitSync(oldest.sync, 5 * SECOND_NS)) { broken = true; return false; }
        shown = oldest.slot;
        inFlight.pop_front();
    }

    if (!firstFrameLogged || width != loggedWidth || height != loggedHeight) {
        LOGI("Vulkan frame bridge: %ux%u through %d hardware buffers", width, height, SLOTS);
        firstFrameLogged = true;
        loggedWidth = width;
        loggedHeight = height;
    }

    lastHandedOut = shown;
    Slot& visible = slots[shown];
    glBindTexture(GL_TEXTURE_2D, visible.texture);
    // Reassocia a imagem: alguns drivers só enxergam o que o Vulkan escreveu depois disso.
    pGlImageTargetTexture2D(GL_TEXTURE_2D, (GLeglImageOES) visible.eglImage);
    glBindTexture(GL_TEXTURE_2D, 0);
    *texture = visible.texture;
    return true;
}

void VulkanContext::submitWithoutCopy(const Pending& frame, const std::vector<VkCommandBuffer>& coreCommands, VkSemaphore signal) {
    // Mesma regra do envio normal: com os command buffers do núcleo, os semáforos de set_image não valem.
    bool useSemaphores = coreCommands.empty() && !frame.semaphores.empty();
    if (coreCommands.empty() && !useSemaphores && signal == VK_NULL_HANDLE) return;
    if (device == VK_NULL_HANDLE || queue == VK_NULL_HANDLE) return;

    std::vector<VkPipelineStageFlags> waitStages(frame.semaphores.size(), VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
    VkSubmitInfo submit = { VK_STRUCTURE_TYPE_SUBMIT_INFO };
    submit.waitSemaphoreCount = useSemaphores ? (uint32_t) frame.semaphores.size() : 0;
    submit.pWaitSemaphores = useSemaphores ? frame.semaphores.data() : nullptr;
    submit.pWaitDstStageMask = useSemaphores ? waitStages.data() : nullptr;
    submit.commandBufferCount = (uint32_t) coreCommands.size();
    submit.pCommandBuffers = coreCommands.empty() ? nullptr : coreCommands.data();
    submit.signalSemaphoreCount = signal != VK_NULL_HANDLE ? 1 : 0;
    submit.pSignalSemaphores = signal != VK_NULL_HANDLE ? &signal : nullptr;

    VkResult result;
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        result = vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE);
    }
    if (result != VK_SUCCESS) {
        LOGE("vkQueueSubmit of the dropped frame failed: %d", result);
    }
}

// endregion

}
