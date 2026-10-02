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

#include "log.h"

#include "audio.h"
#include <algorithm>
#include <cmath>
#include <memory>

namespace libretrodroid {

Audio::Audio(int32_t sampleRate, double refreshRate, bool preferLowLatencyAudio) {
    LOGI("Audio initialization has been called with input sample rate %d", sampleRate);

    contentRefreshRate = refreshRate;
    inputSampleRate = sampleRate;
    audioLatencySettings = findBestLatencySettings(preferLowLatencyAudio);
    initializeStream();
}

bool Audio::initializeStream() {
    LOGI("Using low latency stream: %d", audioLatencySettings->useLowLatencyStream);

    int32_t audioBufferSize = computeAudioBufferSize();

    oboe::AudioStreamBuilder builder;
    builder.setChannelCount(2);
    builder.setDirection(oboe::Direction::Output);
    builder.setFormat(oboe::AudioFormat::I16);
    builder.setDataCallback(this);
    builder.setErrorCallback(this);

    if (audioLatencySettings->useLowLatencyStream) {
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    } else {
        builder.setFramesPerCallback(audioBufferSize / 10);
    }

    // Aberto fora do lock (pode levar dezenas de ms); o novo só recebe callbacks depois do requestStart.
    oboe::ManagedStream newStream;
    oboe::Result result = builder.openManagedStream(newStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to create stream. Error: %s", oboe::convertToText(result));
        std::shared_ptr<Pipeline> empty;
        std::atomic_store(&pipeline, empty);
        std::lock_guard<std::mutex> lock(streamLock);
        stream = nullptr;
        return false;
    }

    auto newPipeline = std::make_shared<Pipeline>();
    newPipeline->baseConversionFactor = (double) inputSampleRate / newStream->getSampleRate();
    newPipeline->fifoBuffer = std::make_unique<oboe::FifoBuffer>(2, audioBufferSize);
    newPipeline->temporaryAudioBuffer = std::unique_ptr<int16_t[]>(new int16_t[audioBufferSize]);
    newPipeline->temporaryAudioBufferSize = audioBufferSize;
    newPipeline->latencyTuner = std::make_unique<oboe::LatencyTuner>(*newStream);

    oboe::ManagedStream oldStream;
    {
        std::lock_guard<std::mutex> lock(streamLock);
        std::atomic_store(&pipeline, newPipeline);
        oldStream = std::move(stream);
        stream = std::move(newStream);
    }
    return true;
}

std::unique_ptr<Audio::AudioLatencySettings> Audio::findBestLatencySettings(bool preferLowLatencyAudio) {
    if (oboe::AudioStreamBuilder::isAAudioRecommended() && preferLowLatencyAudio) {
        return std::make_unique<AudioLatencySettings>(LOW_LATENCY_SETTINGS);
    } else {
        return std::make_unique<AudioLatencySettings>(DEFAULT_LATENCY_SETTINGS);
    }
}

int32_t Audio::computeAudioBufferSize() {
    double maxLatency = computeMaximumLatency();
    LOGI("Average audio latency set to: %f ms", maxLatency * 0.5);
    double sampleRateDivisor = 500.0 / maxLatency;
    return roundToEven(inputSampleRate / sampleRateDivisor);
}

double Audio::computeMaximumLatency() const {
    double maxLatency = (audioLatencySettings->bufferSizeInVideoFrames / contentRefreshRate) * 1000;
    return std::max(maxLatency, 32.0);
}

void Audio::start() {
    std::lock_guard<std::mutex> lock(streamLock);
    startRequested = true;
    if (stream != nullptr)
        stream->requestStart();
}

void Audio::stop() {
    std::lock_guard<std::mutex> lock(streamLock);
    startRequested = false;
    if (stream != nullptr)
        stream->requestStop();
}

void Audio::write(const int16_t *data, size_t frames) {
    // Sem pipeline quando a saída não abriu: o áudio é descartado em vez de derrubar o app.
    std::shared_ptr<Pipeline> current = std::atomic_load(&pipeline);
    if (current == nullptr) return;
    current->fifoBuffer->write(data, (int32_t) (frames * 2));
}

void Audio::setPlaybackSpeed(const double newPlaybackSpeed) {
    playbackSpeed = newPlaybackSpeed;
}

oboe::DataCallbackResult Audio::onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) {
    auto outputArray = reinterpret_cast<int16_t *>(audioData);
    std::shared_ptr<Pipeline> current = std::atomic_load(&pipeline);
    if (current == nullptr || numFrames <= 0) {
        if (numFrames > 0) std::fill(outputArray, outputArray + numFrames * 2, 0);
        return oboe::DataCallbackResult::Continue;
    }

    double dynamicBufferFactor = computeDynamicBufferConversionFactor(*current, 0.001 * numFrames);
    double finalConversionFactor = current->baseConversionFactor * dynamicBufferFactor * playbackSpeed;

    // When using low-latency stream, numFrames is very low (~100) and the dynamic buffer scaling doesn't work with rounding.
    // By keeping track of the "fractional" frames we can keep the error smaller.
    framesToSubmit += numFrames * finalConversionFactor;
    auto currentFramesToSubmit = (int32_t) std::round(framesToSubmit);
    framesToSubmit -= currentFramesToSubmit;

    // Em velocidade alta (avanço rápido) o pedido passava do buffer temporário, e o readNow, que completa com
    // zeros até o tamanho pedido, escrevia além do fim dele. O excedente fica na fila (que descarta quando enche).
    currentFramesToSubmit = std::clamp(currentFramesToSubmit, 0, current->temporaryAudioBufferSize / 2);
    if (currentFramesToSubmit == 0) {
        std::fill(outputArray, outputArray + numFrames * 2, 0);
        return oboe::DataCallbackResult::Continue;
    }

    current->fifoBuffer->readNow(current->temporaryAudioBuffer.get(), currentFramesToSubmit * 2);

    resampler.resample(current->temporaryAudioBuffer.get(), currentFramesToSubmit, outputArray, numFrames);

    current->latencyTuner->tune();

    return oboe::DataCallbackResult::Continue;
}

// To prevent audio buffer overruns or underruns we set up a PI controller. The idea is to run the
// audio slower when the buffer is empty and faster when it's full.
double Audio::computeDynamicBufferConversionFactor(Pipeline& current, double dt) {
    double framesCapacityInBuffer = current.fifoBuffer->getBufferCapacityInFrames();
    double framesAvailableInBuffer = current.fifoBuffer->getFullFramesAvailable();

    // Error is represented by normalized distance to half buffer utilization. Range [-1.0, 1.0]
    double errorMeasure = (framesCapacityInBuffer - 2.0f * framesAvailableInBuffer) / framesCapacityInBuffer;

    errorIntegral += errorMeasure * dt;

    // Wikipedia states that human ear resolution is around 3.6 Hz within the octave of 1000–2000 Hz.
    // This changes continuously, so we should try to keep it a very low value.
    double proportionalAdjustment = std::clamp(kp * errorMeasure, -maxp, maxp);

    // Ki is a lot lower, so it's safe if it exceeds the ear threshold. Hopefully convergence will
    // be slow enough to be not perceptible. We need to battle test this value.
    double integralAdjustment = std::clamp(ki * errorIntegral, -maxi, maxi);

    double finalAdjustment = proportionalAdjustment + integralAdjustment;

    LOGD("Audio speed adjustments (p: %f) (i: %f)", proportionalAdjustment, integralAdjustment);

    return 1.0 - (finalAdjustment);
}

int32_t Audio::roundToEven(int32_t x) {
    return (x / 2) * 2;
}

void Audio::onErrorAfterClose(oboe::AudioStream* oldStream, oboe::Result result) {
    AudioStreamErrorCallback::onErrorAfterClose(oldStream, result);
    LOGI("Stream error in oboe::onErrorAfterClose %s", oboe::convertToText(result));

    if (result != oboe::Result::ErrorDisconnected)
        return;

    initializeStream();
    if (startRequested) {
        start();
    }
}

} //namespace libretrodroid
