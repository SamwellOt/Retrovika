#include "vulkanrendereres3.h"

#include "../../log.h"
#include "../../vulkan/vulkancontext.h"

namespace libretrodroid {

VulkanRendererES3::~VulkanRendererES3() {
    // Os buffers compartilhados são do VulkanContext; esta Video era a única que os usava.
    VulkanContext::getInstance().releaseFrames();
}

void VulkanRendererES3::onNewFrame(const void *data, unsigned width, unsigned height, size_t pitch) {
    GLuint texture = 0;
    // Sem imagem nova (o núcleo não chamou set_image, ou a cópia falhou): fica o quadro anterior.
    if (!VulkanContext::getInstance().present(width, height, &texture)) {
        return;
    }
    currentTexture = texture;

    if (lastFrameSize.first != (int) width || lastFrameSize.second != (int) height || isDirty) {
        for (auto& i : *framebuffers) {
            ES3Utils::deleteFramebuffer(std::move(i));
        }
        framebuffers = ES3Utils::buildShaderPasses(width, height, shaders);
        isDirty = false;
    }

    glBindTexture(GL_TEXTURE_2D, currentTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, shaders.linearTexture ? GL_LINEAR : GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, shaders.linearTexture ? GL_LINEAR : GL_NEAREST);
    glBindTexture(GL_TEXTURE_2D, 0);

    Renderer::onNewFrame(data, width, height, pitch);
}

uintptr_t VulkanRendererES3::getTexture() {
    return currentTexture;
}

uintptr_t VulkanRendererES3::getFramebuffer() {
    return 0;
}

void VulkanRendererES3::setPixelFormat(int pixelFormat) {
    // O quadro vem sempre como RGBA8 do buffer compartilhado.
}

void VulkanRendererES3::updateRenderedResolution(unsigned int width, unsigned int height) {}

bool VulkanRendererES3::rendersInVideoCallback() {
    return false;
}

void VulkanRendererES3::setShaders(ShaderManager::Chain newShaders) {
    this->shaders = newShaders;
    this->isDirty = true;
}

Renderer::PassData VulkanRendererES3::getPassData(unsigned int layer) {
    PassData result;

    if (layer >= 0 && layer < framebuffers->size()) {
        result.framebuffer = framebuffers->at(layer)->framebuffer;
        result.width = framebuffers->at(layer)->width;
        result.height = framebuffers->at(layer)->height;
    }

    if (layer > 0 && layer < framebuffers->size() + 1) {
        result.texture = framebuffers->at(layer - 1)->texture;
    }

    return result;
}

} //namespace libretrodroid
