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

#include <cmath>
#include "fpssync.h"
#include "log.h"

namespace libretrodroid {

unsigned FPSSync::advanceFrames() {
    if (useVSync) {
        // Tela múltipla do conteúdo: roda no primeiro vsync do ciclo e nos outros só reapresenta o quadro.
        if (vsyncDivisor <= 1) return 1;
        return (vsyncCycle++ % vsyncDivisor) == 0 ? 1 : 0;
    }

    if (lastFrame == MIN_TIME) {
        start();
    }

    auto now = std::chrono::steady_clock::now();
    auto frames = std::max((now - lastFrame) / sampleInterval, (long long) 1);
    lastFrame = lastFrame + sampleInterval * frames;

    return frames;
}

FPSSync::FPSSync(double contentRefreshRate, double screenRefreshRate) {
    this->contentRefreshRate = contentRefreshRate;
    this->screenRefreshRate = screenRefreshRate;
    // A mesma tolerância sobre a taxa efetiva (tela / n): 120 Hz com 60 fps roda um quadro a cada 2 vsyncs, em vez
    // de cair na espera por sleep (trepidação e CPU acordando a 120 Hz). O menor n que serve vence.
    this->useVSync = false;
    for (unsigned n = 1; n <= MAX_VSYNC_DIVISOR && contentRefreshRate > 0; n++) {
        if (std::abs(screenRefreshRate / n - contentRefreshRate) < FPS_TOLERANCE) {
            this->useVSync = true;
            this->vsyncDivisor = n;
            break;
        }
    }
    this->sampleInterval = std::chrono::microseconds((long) ((1000000L / contentRefreshRate)));
    LOGI("Content fps %f on a screen with refresh rate %f: vsync %d (one frame every %u vsyncs)",
         contentRefreshRate, screenRefreshRate, useVSync, vsyncDivisor);
    reset();
}

void FPSSync::start() {
    lastFrame = std::chrono::steady_clock::now();
}

void FPSSync::reset() {
    lastFrame = MIN_TIME;
    vsyncCycle = 0;
}

double FPSSync::getTimeStretchFactor() const {
    // Com n > 1 o núcleo roda na taxa da tela dividida por n: é com ela que o conteúdo é comparado.
    return useVSync ? contentRefreshRate / getFrameRate() : 1.0;
}

} //namespace libretrodroid
