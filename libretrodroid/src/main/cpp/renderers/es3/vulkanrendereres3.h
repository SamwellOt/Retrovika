#ifndef LIBRETRODROID_VULKANRENDERERES3_H
#define LIBRETRODROID_VULKANRENDERERES3_H

#include "../renderer.h"
#include "es3utils.h"

#include "GLES3/gl3.h"

#include <cstdint>
#include <memory>

namespace libretrodroid {

/**
 * Quadros de núcleos Vulkan: o VulkanContext copia a imagem do núcleo para um AHardwareBuffer e entrega a
 * textura GL que o enxerga. A partir daí é uma textura comum (origem no topo, como os quadros de software),
 * e a cadeia de shaders é a de sempre.
 */
class VulkanRendererES3: public Renderer {
public:
    VulkanRendererES3() = default;
    ~VulkanRendererES3() override;

    uintptr_t getTexture() override;
    uintptr_t getFramebuffer() override;
    void onNewFrame(const void *data, unsigned width, unsigned height, size_t pitch) override;
    void setPixelFormat(int pixelFormat) override;
    void updateRenderedResolution(unsigned int width, unsigned int height) override;
    bool rendersInVideoCallback() override;
    void setShaders(ShaderManager::Chain shaders) override;
    PassData getPassData(unsigned int layer) override;

private:
    unsigned int currentTexture = 0;
    bool isDirty = true;

    ShaderManager::Chain shaders;
    std::unique_ptr<ES3Utils::Framebuffers> framebuffers = std::make_unique<ES3Utils::Framebuffers>();
};

}

#endif //LIBRETRODROID_VULKANRENDERERES3_H
