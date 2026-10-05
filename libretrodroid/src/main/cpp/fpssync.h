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

#ifndef LIBRETRODROID_FPSSYNC_H
#define LIBRETRODROID_FPSSYNC_H

#include <chrono>
#include <thread>

namespace libretrodroid {

typedef std::chrono::steady_clock::time_point TimePoint;
typedef std::chrono::duration<long, std::micro> Duration;

class FPSSync {
public:
    FPSSync(double contentRefreshRate, double screenRefreshRate);
    ~FPSSync() { }

    void reset();
    /**
     * Quando o próximo quadro deve começar, no modo de espera. A thread dorme até aí ANTES de rodar o quadro
     * (e fora de qualquer lock), para o desenho terminado ser apresentado logo. TimePoint::min() se pode rodar já.
     */
    TimePoint nextStart() const { return useVSync ? MIN_TIME : lastFrame; }
    /** Quantos quadros do núcleo rodar neste desenho. 0 = só reapresentar o último (tela múltipla do conteúdo). */
    unsigned advanceFrames();
    double getTimeStretchFactor() const;
    bool isUsingVSync() const { return useVSync; }
    /** Roda um quadro a cada [n] vsyncs (n > 1: tela a 120 Hz com conteúdo de 60). 1 no vsync simples. */
    unsigned getVsyncDivisor() const { return vsyncDivisor; }
    double getScreenRefreshRate() const { return screenRefreshRate; }
    /** Quadros por segundo em que o núcleo realmente roda: o vsync dividido, ou o do conteúdo no modo de espera. */
    double getFrameRate() const { return useVSync ? screenRefreshRate / vsyncDivisor : contentRefreshRate; }
private:

    double screenRefreshRate;
    double contentRefreshRate;
    bool useVSync;
    unsigned vsyncDivisor = 1;
    // Posição do desenho dentro do ciclo de [vsyncDivisor] vsyncs.
    unsigned vsyncCycle = 0;
    const double FPS_TOLERANCE = 5;
    static constexpr unsigned MAX_VSYNC_DIVISOR = 4;

    const TimePoint MIN_TIME = TimePoint::min();
    void start();

    TimePoint lastFrame = MIN_TIME;
    Duration sampleInterval;
};

}


#endif //LIBRETRODROID_FPSSYNC_H
