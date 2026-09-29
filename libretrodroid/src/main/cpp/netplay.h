/*
 * Partida em rede local (Retrovika): lockstep com atraso de entrada.
 *
 * Os dois aparelhos rodam o mesmo núcleo a partir do mesmo estado. Antes de cada retro_run do quadro F,
 * cada lado já mandou ao outro a própria entrada de F (ela foi lida D quadros antes, no quadro F - D) e
 * só roda F quando a do outro para F chegou. Assim os dois veem exatamente a mesma entrada em cada
 * quadro, e núcleos determinísticos ficam iguais. O atraso D esconde a latência da rede local.
 *
 * O soquete é TCP (já conectado pelo lado Java, que também troca o estado inicial) e fica sem bloqueio:
 * se a entrada do outro ainda não chegou, o quadro é pulado e tentado de novo no próximo desenho, então a
 * thread de emulação nunca fica presa esperando a rede (pausar o app continua funcionando).
 */

#ifndef LIBRETRODROID_NETPLAY_H
#define LIBRETRODROID_NETPLAY_H

#include <atomic>
#include <chrono>
#include <cstdint>
#include <string>
#include <unordered_map>

#include "input.h"

namespace libretrodroid {

class Netplay {
public:
    struct Pad {
        uint16_t buttons = 0;
        int16_t analog[4] = {0, 0, 0, 0};   // esquerdo x/y, direito x/y
        int16_t pointer[2] = {0, 0};        // x/y na faixa do libretro
        uint8_t pointerPressed = 0;
    };

    /** [localPort]: 0 no anfitrião, 1 no convidado. [epoch] separa sessões no mesmo soquete (ressincronizar). */
    Netplay(int fd, unsigned localPort, unsigned delayFrames, uint8_t epoch);
    ~Netplay();

    /**
     * Antes do retro_run: manda a entrada local do quadro atual + atraso e devolve se a do outro para o
     * quadro atual já chegou (senão o quadro é pulado).
     */
    bool prepareFrame(Input* input);
    /** Depois do retro_run: passa para o próximo quadro. */
    void frameDone();

    int16_t getInputState(unsigned port, unsigned device, unsigned index, unsigned id) const;

    uint32_t currentFrame() const { return frame.load(); }
    bool isBroken() const { return broken.load(); }
    /** Há quanto tempo (ms) a entrada do outro está atrasada; 0 quando em dia. */
    int64_t stalledMillis() const;

private:
    static constexpr size_t PACKET_SIZE = 21;
    static constexpr uint8_t MAGIC = 'R';
    // Conexão morta (o outro aparelho sumiu da rede sem fechar o soquete): ~20 s para desistir.
    static constexpr int KEEPALIVE_IDLE_S = 5;
    static constexpr int KEEPALIVE_INTERVAL_S = 3;
    static constexpr int KEEPALIVE_COUNT = 5;
    static constexpr int64_t DEAD_PEER_MS = 20000;

    Pad capture(Input* input) const;
    void send(uint32_t forFrame, const Pad& pad);
    void pump();
    void flush();

    int fd;
    unsigned localPort;
    unsigned delay;
    uint8_t epoch;

    std::atomic<uint32_t> frame {0};
    std::atomic<bool> broken {false};
    std::atomic<int64_t> stalledSince {0};
    int64_t lastSentFrame = -1;

    std::unordered_map<uint32_t, Pad> localPads;
    std::unordered_map<uint32_t, Pad> remotePads;
    Pad current[2];

    std::string inBuffer;
    std::string outBuffer;
};

}

#endif //LIBRETRODROID_NETPLAY_H
