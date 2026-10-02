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

#ifndef LIBRETRODROID_AUDIO_H
#define LIBRETRODROID_AUDIO_H

#include <array>
#include <atomic>
#include <memory>
#include <mutex>
#include <unistd.h>
#include <oboe/Oboe.h>
#include <oboe/FifoBuffer.h>

#include "resamplers/linearresampler.h"

namespace libretrodroid {

class Audio: public oboe::AudioStreamDataCallback, oboe::AudioStreamErrorCallback {
private:
    struct AudioLatencySettings {
        unsigned bufferSizeInVideoFrames;
        bool useLowLatencyStream;
    };

    const AudioLatencySettings DEFAULT_LATENCY_SETTINGS { 8, false };
    const AudioLatencySettings LOW_LATENCY_SETTINGS { 4, true };

public:
    Audio(int32_t sampleRate, double refreshRate, bool preferLowLatencyAudio);
    ~Audio() override = default;

    void start();
    void stop();

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream *oboeStream,
        void *audioData,
        int32_t numFrames
    ) override;

    void onErrorAfterClose(oboe::AudioStream *oldStream, oboe::Result result) override;

public:
    void write(const int16_t *data, size_t frames);
    void setPlaybackSpeed(const double newPlaybackSpeed);

private:
    static int32_t roundToEven(int32_t x);
    struct Pipeline;
    double computeDynamicBufferConversionFactor(Pipeline& pipeline, double dt);
    int32_t computeAudioBufferSize();
    bool initializeStream();
    std::unique_ptr<Audio::AudioLatencySettings> findBestLatencySettings(bool preferLowLatencyAudio);
    double computeMaximumLatency() const;

private:
    const double kp = 0.006;
    const double ki = 0.00002;
    const double maxp = 0.003;
    const double maxi = 0.02;

    LinearResampler resampler;

    /**
     * O que a thread de emulação (write) e a do Oboe (onAudioReady) usam juntas. Quando o dispositivo de saída
     * muda, o onErrorAfterClose monta um conjunto novo e o troca de uma vez (std::atomic_store): quem estava no
     * meio de um write ou de um callback termina com o antigo, sem lock no caminho do áudio.
     */
    struct Pipeline {
        std::unique_ptr<oboe::FifoBuffer> fifoBuffer;
        std::unique_ptr<int16_t[]> temporaryAudioBuffer;
        // Em amostras (int16), não em quadros estéreo.
        int32_t temporaryAudioBufferSize = 0;
        std::unique_ptr<oboe::LatencyTuner> latencyTuner;
        double baseConversionFactor = 1.0;
    };
    std::shared_ptr<Pipeline> pipeline;

    // Protege [stream]: start/stop vêm da thread principal e a reabertura vem da thread de erro do Oboe.
    std::mutex streamLock;
    oboe::ManagedStream stream = nullptr;

    std::atomic<bool> startRequested {false};
    int32_t inputSampleRate;
    double contentRefreshRate = 60.0;

    double framesToSubmit = 0.0;
    double errorIntegral = 0.0;

    double playbackSpeed = 1.0;

    std::unique_ptr<AudioLatencySettings> audioLatencySettings;
};

} // namespace libretrodroid

#endif //LIBRETRODROID_AUDIO_H
