#ifndef LIBRETRODROID_VULKANCONTEXT_H
#define LIBRETRODROID_VULKANCONTEXT_H

#include <array>
#include <atomic>
#include <cstdint>
#include <deque>
#include <mutex>
#include <vector>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>

#include "../libretro/libretro-common/include/libretro.h"
#include "../libretro/libretro-common/include/libretro_vulkan.h"

namespace libretrodroid {

/**
 * Contexto Vulkan para núcleos libretro que renderizam com Vulkan (RETRO_HW_CONTEXT_VULKAN).
 *
 * O núcleo desenha numa VkImage dele. A apresentação do LibretroDroid é toda em GLES (shaders, rotação,
 * captura para transmissão), então o quadro vai para ela por um AHardwareBuffer: cada quadro é copiado
 * (vkCmdBlitImage) para um buffer do frontend, que o GL importa como EGLImage e amostra como uma textura
 * qualquer. Sem swapchain, sem superfície Vulkan.
 *
 * A instância e o dispositivo são criados pelo frontend, ou pelo núcleo pela interface de negociação
 * (o LRPS2 exige: escolhe as extensões e features que quer). Tudo roda na thread de emulação (GL).
 */
class VulkanContext {
public:
    static VulkanContext& getInstance();

    /**
     * O aparelho entrega o que a ponte precisa: loader Vulkan 1.1, um dispositivo com
     * VK_ANDROID_external_memory_android_hardware_buffer e as extensões de EGL/GL para importar o buffer.
     * Chamar com o contexto EGL atual. O resultado fica guardado.
     */
    static bool isAvailable();

    /**
     * [isAvailable] com um contexto EGL próprio e descartável (uma superfície de 1x1): para o app saber, antes de
     * abrir qualquer jogo e fora da thread GL, se a ponte funciona neste aparelho. Pode ser chamado de qualquer thread.
     */
    static bool probeWithOwnContext();

    /** Interface de negociação do núcleo (SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE); copiada. */
    void setNegotiation(const retro_hw_render_context_negotiation_interface_vulkan* negotiation);

    /** Cria instância, dispositivo e fila. Idempotente. Falso se não deu (o chamador falha o carregamento). */
    bool create();

    /** Libera tudo, na ordem que a interface pede (destroy_device do núcleo antes do dispositivo e da instância). */
    void destroy();

    /** A interface para GET_HW_RENDER_INTERFACE; nula antes do create(). */
    const retro_hw_render_interface_vulkan* renderInterface() const { return ready ? &iface : nullptr; }

    /**
     * Leva o quadro que o núcleo entregou (set_image) para uma textura GL. [texture] recebe a textura a
     * amostrar, com a imagem ligada. Falso se o núcleo não entregou imagem ou a cópia falhou: vale o quadro
     * anterior.
     */
    bool present(unsigned width, unsigned height, GLuint* texture);

    /** Solta os buffers compartilhados (a Video que os usava vai ser destruída). */
    void releaseFrames();

    /**
     * A ponte estava funcionando e quebrou (espera da GPU estourou, envio falhou, sem memória para os buffers):
     * daqui em diante só sairia tela preta. Lido pela thread de emulação para avisar o app.
     */
    bool isLost() const { return ready && broken.load(); }

private:
    VulkanContext() = default;

    // Interface para o núcleo.
    static void setImage(void* handle, const retro_vulkan_image* image, uint32_t numSemaphores, const VkSemaphore* semaphores, uint32_t srcQueueFamily);
    static uint32_t getSyncIndex(void* handle);
    static uint32_t getSyncIndexMask(void* handle);
    static void setCommandBuffers(void* handle, uint32_t num, const VkCommandBuffer* cmd);
    static void waitSyncIndex(void* handle);
    static void lockQueue(void* handle);
    static void unlockQueue(void* handle);
    static void setSignalSemaphore(void* handle, VkSemaphore semaphore);

    bool createInstance();
    bool createDevice();
    bool createDeviceDefault(VkPhysicalDevice gpu);
    bool pickPhysicalDevice(VkPhysicalDevice* gpu);
    bool createFrameResources();
    void fillInterface();

    // Quadros compartilhados.
    struct Slot {
        AHardwareBuffer* buffer = nullptr;
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        EGLImageKHR eglImage = EGL_NO_IMAGE_KHR;
        GLuint texture = 0;
        GLsync glFence = nullptr;
        // Onde a textura e a fence foram criadas: fora desse contexto os nomes GL não valem.
        EGLDisplay display = EGL_NO_DISPLAY;
        EGLContext glContext = EGL_NO_CONTEXT;
    };
    struct InFlight {
        int slot;
        uint32_t sync;
    };

    bool ensureSlots(unsigned width, unsigned height);
    bool createSlot(Slot& slot, unsigned width, unsigned height);
    void destroySlot(Slot& slot);
    bool waitSync(uint32_t sync, uint64_t timeoutNs);
    int pickFreeSlot() const;
    struct Pending;
    void recordCopy(VkCommandBuffer cmd, const Pending& frame, VkImage dst, unsigned width, unsigned height);
    void submitWithoutCopy(const Pending& frame, const std::vector<VkCommandBuffer>& coreCommands, VkSemaphore signal);

    static constexpr uint32_t SYNC_IMAGES = 3;
    static constexpr int SLOTS = 4;

    bool ready = false;
    std::atomic<bool> broken {false};

    // Negociação.
    bool hasNegotiation = false;
    retro_hw_render_context_negotiation_interface_vulkan negotiation {};
    bool deviceCreatedByCore = false;

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice gpu = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
    PFN_vkGetDeviceProcAddr getDeviceProcAddr = nullptr;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getBufferProperties = nullptr;

    retro_hw_render_interface_vulkan iface {};
    std::mutex queueMutex;
    std::mutex fenceMutex;

    VkCommandPool commandPool = VK_NULL_HANDLE;
    std::array<VkCommandBuffer, SYNC_IMAGES> commandBuffers {};
    std::array<VkFence, SYNC_IMAGES> fences {};
    std::array<bool, SYNC_IMAGES> fenceSubmitted {};
    uint32_t syncIndex = 0;

    // O que o núcleo entregou para o quadro atual.
    struct Pending {
        bool valid = false;
        VkImage image = VK_NULL_HANDLE;
        VkImageLayout layout = VK_IMAGE_LAYOUT_UNDEFINED;
        VkImageSubresourceRange range {};
        uint32_t srcQueueFamily = VK_QUEUE_FAMILY_IGNORED;
        std::vector<VkSemaphore> semaphores;
    } pending;
    std::vector<VkCommandBuffer> coreCommandBuffers;
    VkSemaphore signalSemaphore = VK_NULL_HANDLE;

    std::array<Slot, SLOTS> slots {};
    unsigned slotWidth = 0;
    unsigned slotHeight = 0;
    std::deque<InFlight> inFlight;
    int shown = -1;
    int lastHandedOut = -1;
    bool firstFrameLogged = false;
    unsigned loggedWidth = 0;
    unsigned loggedHeight = 0;
};

}

#endif //LIBRETRODROID_VULKANCONTEXT_H
