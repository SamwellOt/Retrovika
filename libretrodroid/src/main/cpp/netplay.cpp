#include "netplay.h"

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include "libretro.h"
#include "log.h"

namespace libretrodroid {

static int64_t nowMillis() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

Netplay::Netplay(int fd, unsigned localPort, unsigned delayFrames, uint8_t epoch)
    : fd(fd), localPort(localPort > 0 ? 1 : 0), delay(delayFrames), epoch(epoch) {
    int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    // Queda de conexão é detectada pelo próprio TCP, não pela falta de entrada: um lado pausado (menu,
    // ligação) para de mandar entrada, mas o sistema dele continua respondendo ao TCP. Keep-alive acha o
    // outro aparelho sumido quando nada está sendo enviado; TCP_USER_TIMEOUT, quando há dados sem resposta.
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));
#ifdef TCP_KEEPIDLE
    int idle = KEEPALIVE_IDLE_S;
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof(idle));
#endif
#ifdef TCP_KEEPINTVL
    int interval = KEEPALIVE_INTERVAL_S;
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &interval, sizeof(interval));
#endif
#ifdef TCP_KEEPCNT
    int count = KEEPALIVE_COUNT;
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &count, sizeof(count));
#endif
#ifdef TCP_USER_TIMEOUT
    unsigned int userTimeout = (unsigned int) DEAD_PEER_MS;
    setsockopt(fd, IPPROTO_TCP, TCP_USER_TIMEOUT, &userTimeout, sizeof(userTimeout));
#endif
    // Os primeiros quadros não têm entrada de ninguém ainda: os dois lados começam parados.
    for (uint32_t f = 0; f < delay; f++) {
        localPads[f] = Pad();
        remotePads[f] = Pad();
    }
    lastSentFrame = (int64_t) delay - 1;
    LOGI("Netplay started: port %u, delay %u, epoch %u", this->localPort, delay, epoch);
}

Netplay::~Netplay() {
    if (fd >= 0) close(fd);
}

Netplay::Pad Netplay::capture(Input* input) const {
    Pad pad;
    if (input == nullptr) return pad;
    for (unsigned b = 0; b < 16; b++) {
        if (input->getInputState(0, RETRO_DEVICE_JOYPAD, 0, b)) pad.buttons |= (uint16_t) (1u << b);
    }
    pad.analog[0] = input->getInputState(0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_X);
    pad.analog[1] = input->getInputState(0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_Y);
    pad.analog[2] = input->getInputState(0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_X);
    pad.analog[3] = input->getInputState(0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_Y);
    pad.pointer[0] = input->getInputState(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_X);
    pad.pointer[1] = input->getInputState(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_Y);
    pad.pointerPressed = input->getInputState(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_PRESSED) ? 1 : 0;
    return pad;
}

static void put16(std::string& out, uint16_t v) {
    out.push_back((char) (v & 0xFF));
    out.push_back((char) ((v >> 8) & 0xFF));
}

static uint16_t get16(const std::string& in, size_t at) {
    return (uint16_t) ((uint8_t) in[at] | ((uint16_t) (uint8_t) in[at + 1] << 8));
}

void Netplay::send(uint32_t forFrame, const Pad& pad) {
    outBuffer.push_back((char) MAGIC);
    outBuffer.push_back((char) epoch);
    put16(outBuffer, (uint16_t) (forFrame & 0xFFFF));
    put16(outBuffer, (uint16_t) (forFrame >> 16));
    put16(outBuffer, pad.buttons);
    for (int16_t a : pad.analog) put16(outBuffer, (uint16_t) a);
    for (int16_t p : pad.pointer) put16(outBuffer, (uint16_t) p);
    outBuffer.push_back((char) pad.pointerPressed);
    flush();
}

void Netplay::flush() {
    while (!outBuffer.empty() && !broken) {
        ssize_t n = ::send(fd, outBuffer.data(), outBuffer.size(), MSG_NOSIGNAL | MSG_DONTWAIT);
        if (n > 0) {
            outBuffer.erase(0, (size_t) n);
        } else if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
            return;
        } else {
            LOGE("Netplay send failed: %s", strerror(errno));
            broken = true;
        }
    }
}

void Netplay::pump() {
    flush();
    char buffer[4096];
    while (!broken) {
        ssize_t n = recv(fd, buffer, sizeof(buffer), MSG_DONTWAIT);
        if (n > 0) {
            inBuffer.append(buffer, (size_t) n);
        } else if (n == 0) {
            LOGI("Netplay: the other side closed the connection");
            broken = true;
        } else if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
            break;
        } else {
            LOGE("Netplay recv failed: %s", strerror(errno));
            broken = true;
        }
    }
    size_t at = 0;
    while (inBuffer.size() - at >= PACKET_SIZE) {
        if ((uint8_t) inBuffer[at] != MAGIC) { at++; continue; }
        uint8_t packetEpoch = (uint8_t) inBuffer[at + 1];
        uint32_t packetFrame = get16(inBuffer, at + 2) | ((uint32_t) get16(inBuffer, at + 4) << 16);
        Pad pad;
        pad.buttons = get16(inBuffer, at + 6);
        for (int i = 0; i < 4; i++) pad.analog[i] = (int16_t) get16(inBuffer, at + 8 + i * 2);
        for (int i = 0; i < 2; i++) pad.pointer[i] = (int16_t) get16(inBuffer, at + 16 + i * 2);
        pad.pointerPressed = (uint8_t) inBuffer[at + 20];
        // Pacotes de uma sessão anterior (antes de ressincronizar) ficam de fora. Também os de quadros longe
        // demais: o outro só roda um quadro com a nossa entrada dele, que vai no máximo [delay] à frente, e
        // manda a dele [delay] à frente disso (+1 do quadro que acabou de rodar). Um quadro absurdo (pacote
        // corrompido) ficaria no mapa para sempre, já que só o quadro atual é apagado.
        uint64_t maxFrame = (uint64_t) frame.load() + 2ull * delay + MAX_FRAMES_AHEAD_MARGIN;
        if (packetEpoch == epoch && packetFrame >= frame && packetFrame <= maxFrame) remotePads[packetFrame] = pad;
        at += PACKET_SIZE;
    }
    inBuffer.erase(0, at);
}

bool Netplay::prepareFrame(const Pad& localPad) {
    if (broken) return false;
    uint32_t f = frame;
    int64_t target = (int64_t) f + delay;
    if (lastSentFrame < target) {
        localPads[(uint32_t) target] = localPad;
        send((uint32_t) target, localPad);
        lastSentFrame = target;
    }
    pump();
    if (remotePads.find(f) == remotePads.end() && !broken) {
        // Na rede local a entrada costuma chegar em poucos milissegundos: uma espera curta evita pular o quadro.
        pollfd p { fd, POLLIN, 0 };
        if (poll(&p, 1, 6) > 0) pump();
    }
    auto remote = remotePads.find(f);
    auto local = localPads.find(f);
    if (remote == remotePads.end() || local == localPads.end()) {
        // Sem prazo aqui: o outro lado pode estar pausado por quanto tempo quiser. A conexão só cai quando
        // o soquete falha (recv/send com erro, fim da conexão, keep-alive ou TCP_USER_TIMEOUT).
        if (stalledSince.load() == 0) stalledSince = nowMillis();
        return false;
    }
    stalledSince = 0;
    current[localPort] = local->second;
    current[1 - localPort] = remote->second;
    return true;
}

void Netplay::frameDone() {
    uint32_t f = frame;
    localPads.erase(f);
    remotePads.erase(f);
    frame = f + 1;
}

int64_t Netplay::stalledMillis() const {
    int64_t since = stalledSince.load();
    return since == 0 ? 0 : nowMillis() - since;
}

int16_t Netplay::getInputState(unsigned port, unsigned device, unsigned index, unsigned id) const {
    if (port > 1) return 0;
    const Pad& pad = current[port];
    switch (device) {
        case RETRO_DEVICE_JOYPAD:
            if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t) pad.buttons;
            if (id < 16) return (pad.buttons >> id) & 1;
            return 0;
        case RETRO_DEVICE_ANALOG:
            if (index > 1 || id > 1) return 0;
            return pad.analog[index * 2 + id];
        case RETRO_DEVICE_POINTER:
            if (index > 0) return 0;
            switch (id) {
                case RETRO_DEVICE_ID_POINTER_X: return pad.pointer[0];
                case RETRO_DEVICE_ID_POINTER_Y: return pad.pointer[1];
                case RETRO_DEVICE_ID_POINTER_PRESSED: return pad.pointerPressed;
                default: return 0;
            }
        default:
            return 0;
    }
}

}
