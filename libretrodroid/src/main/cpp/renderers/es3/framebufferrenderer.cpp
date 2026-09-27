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

#include "framebufferrenderer.h"
#include "es3utils.h"
#include "../../log.h"

namespace libretrodroid {

FramebufferRenderer::FramebufferRenderer(
    unsigned width,
    unsigned height,
    bool depth,
    bool stencil,
    ShaderManager::Chain shaders
) {
    this->depth = depth;
    this->stencil = stencil;
    this->width = width;
    this->height = height;
    this->shaders = std::move(shaders);

    initializeBuffers();
}

void FramebufferRenderer::onNewFrame(const void *data, unsigned width, unsigned height, size_t pitch) {
    // The core draws only the bottom-left width x height area of its framebuffer.
    width = std::max(1u, std::min(width, framebuffer->width));
    height = std::max(1u, std::min(height, framebuffer->height));
    Renderer::onNewFrame(data, width, height, pitch);
    hasPendingFrame = true;
}

void FramebufferRenderer::prepareFrame() {
    // Resizing here (and not in onNewFrame) keeps these GL calls inside the saved state.
    if (isDirty) {
        initializeBuffers();
        isDirty = false;
    }

    auto frameWidth = (unsigned) lastFrameSize.first;
    auto frameHeight = (unsigned) lastFrameSize.second;
    if (frameWidth == 0 || frameHeight == 0) return;

    if (isPresentDirty || presentFramebuffer->width != frameWidth || presentFramebuffer->height != frameHeight) {
        initializePresentBuffers(frameWidth, frameHeight);
        isPresentDirty = false;
        hasPendingFrame = true;
    }

    if (!hasPendingFrame) return;
    hasPendingFrame = false;

    glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer->framebuffer);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, presentFramebuffer->framebuffer);
    glBlitFramebuffer(
        0, 0, (GLint) frameWidth, (GLint) frameHeight,
        0, 0, (GLint) frameWidth, (GLint) frameHeight,
        GL_COLOR_BUFFER_BIT,
        GL_NEAREST
    );
}

void FramebufferRenderer::initializeBuffers() {
    GLint maxSize = 0;
    glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE, &maxSize);
    if (maxSize > 0) {
        width = std::min(width, (unsigned) maxSize);
        height = std::min(height, (unsigned) maxSize);
    }

    ES3Utils::deleteFramebuffer(std::move(framebuffer));
    framebuffer = ES3Utils::createFramebuffer(
        width,
        height,
        shaders.linearTexture,
        false,
        depth,
        stencil
    );

    // The new framebuffer starts undefined: nothing to show until the core draws into it.
    hasPendingFrame = false;
}

void FramebufferRenderer::initializePresentBuffers(unsigned int width, unsigned int height) {
    framebuffers = ES3Utils::buildShaderPasses(width, height, shaders);

    ES3Utils::deleteFramebuffer(std::move(presentFramebuffer));
    presentFramebuffer = ES3Utils::createFramebuffer(
        width,
        height,
        shaders.linearTexture,
        false,
        false,
        false
    );
}

uintptr_t FramebufferRenderer::getTexture() {
    return presentFramebuffer->texture;
}

uintptr_t FramebufferRenderer::getFramebuffer() {
    return framebuffer->framebuffer;
}

void FramebufferRenderer::setPixelFormat(int pixelFormat) {
    // TODO... Here we should handle 32bit framebuffers.
}

void FramebufferRenderer::updateRenderedResolution(unsigned int width, unsigned int height) {
    // Only grows: shrinking would cut frames the core is still allowed to render at max size,
    // and every reallocation changes the framebuffer id under the core.
    if (width > this->width || height > this->height) {
        this->width = std::max(width, this->width);
        this->height = std::max(height, this->height);
        isDirty = true;
    }
}

bool FramebufferRenderer::rendersInVideoCallback() {
    return true;
}

void FramebufferRenderer::setShaders(ShaderManager::Chain shaders) {
    if (shaders != this->shaders) {
        this->shaders = shaders;
        // The present texture (filtering) and the shader passes depend on the shaders.
        isPresentDirty = true;
    }
}

Renderer::PassData FramebufferRenderer::getPassData(unsigned int layer) {
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
