#!/usr/bin/env python3
"""
Gera uma ROM de Game Boy que serve de teste para a tradução dentro do jogo: um "diálogo" de 40 letras mora na WRAM
($C000, o começo da RAM do sistema), e a cada quadro o jogo o redesenha na tela a partir dali. O botão A troca a
mensagem. Assim, escrever um texto novo na memória muda o que aparece na tela, como num diálogo de verdade.

Uso: python3 -I tools/make-dialog-test-rom.py saida.gb [prévia.png]
Os glifos vêm da DejaVu Sans Mono (licença livre); não há código de terceiros.
"""
import sys
from PIL import Image, ImageDraw, ImageFont

MESSAGES = [
    "Hello World",
    "The cat sat on a mat by the door",
    "Good morning, friend! Welcome home.",
]
MSG_LEN = 40
FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"


def glyph_tiles():
    font = ImageFont.truetype(FONT_PATH, 9)
    tiles = bytearray()
    preview = Image.new("L", (8 * 32, 8 * 3), 255)
    for index in range(96):
        char = chr(32 + index) if index < 95 else " "
        img = Image.new("L", (8, 8), 255)
        ImageDraw.Draw(img).text((0, -1), char, font=font, fill=0)
        bits = [[1 if img.getpixel((x, y)) < 140 else 0 for x in range(8)] for y in range(8)]
        for y in range(8):
            row = 0
            for x in range(8):
                row = (row << 1) | bits[y][x]
            tiles += bytes([row, row])          # os dois planos iguais: cor 3 (preto) sobre a 0 (branco)
        preview.paste(img, ((index % 32) * 8, (index // 32) * 8))
    return bytes(tiles), preview


class Asm:
    def __init__(self, origin):
        self.origin = origin
        self.out = bytearray()
        self.labels = {}
        self.fixups = []   # (posição, tipo, rótulo)

    def label(self, name): self.labels[name] = self.origin + len(self.out)
    def b(self, *values): self.out += bytes(values)
    def jr(self, opcode, name): self.b(opcode, 0); self.fixups.append((len(self.out) - 1, "rel", name))
    def abs(self, opcode, name): self.b(opcode, 0, 0); self.fixups.append((len(self.out) - 2, "abs", name))

    def resolve(self):
        for pos, kind, name in self.fixups:
            target = self.labels[name]
            if kind == "rel":
                delta = target - (self.origin + pos + 1)
                assert -128 <= delta <= 127, f"salto longo demais para {name}: {delta}"
                self.out[pos] = delta & 0xFF
            else:
                self.out[pos] = target & 0xFF
                self.out[pos + 1] = target >> 8
        return bytes(self.out)


def build():
    tiles, preview = glyph_tiles()
    a = Asm(0x150)
    FONT, MSGS = 0x0400, 0x0A00

    a.b(0xF3)                                   # di
    a.b(0x31, 0xFE, 0xFF)                       # ld sp,$FFFE
    a.label("wait1"); a.b(0xF0, 0x44); a.b(0xFE, 144); a.jr(0x38, "wait1")   # espera o VBlank
    a.b(0xAF); a.b(0xE0, 0x40)                  # LCD desligado
    a.b(0x21, FONT & 0xFF, FONT >> 8); a.b(0x11, 0x00, 0x80); a.b(0x01, 0x00, 0x06)   # hl=fonte de=$8000 bc=1536
    a.label("cpf"); a.b(0x2A, 0x12, 0x13, 0x0B, 0x78, 0xB1); a.jr(0x20, "cpf")
    a.b(0x21, 0x00, 0x98); a.b(0x01, 0x00, 0x04)                               # limpa o mapa $9800
    a.label("clr"); a.b(0xAF, 0x22, 0x0B, 0x78, 0xB1); a.jr(0x20, "clr")
    a.b(0x3E, 0xE4); a.b(0xE0, 0x47)            # paleta
    a.b(0xAF); a.b(0xEA, 0x00, 0xC1); a.b(0xEA, 0x01, 0xC1)                    # índice=0, botão anterior=0
    a.abs(0xCD, "loadmsg")
    a.b(0x3E, 0x91); a.b(0xE0, 0x40)            # LCD ligado, fundo em $8000, mapa $9800

    a.label("main"); a.b(0xF0, 0x44); a.b(0xFE, 144); a.jr(0x20, "main")
    a.b(0x21, 0x00, 0xC0); a.b(0x11, 0x00, 0x99); a.abs(0xCD, "drawrow")        # linha 1
    a.b(0x11, 0x20, 0x99); a.abs(0xCD, "drawrow")                               # linha 2 (hl segue em $C014)
    a.b(0x3E, 0x10); a.b(0xE0, 0x00); a.b(0xF0, 0x00); a.b(0xF0, 0x00)         # lê os botões
    a.b(0x2F); a.b(0xE6, 0x01)                  # cpl; and 1 -> 1 quando A está apertado
    a.b(0x47); a.b(0xFA, 0x01, 0xC1); a.b(0x4F); a.b(0x78); a.b(0xEA, 0x01, 0xC1)   # b=agora c=antes; guarda
    a.b(0x79, 0xB7); a.jr(0x20, "done")         # já estava apertado: nada
    a.b(0x78, 0xB7); a.jr(0x28, "done")         # não está apertado: nada
    a.b(0xFA, 0x00, 0xC1); a.b(0x3C); a.b(0xFE, len(MESSAGES)); a.jr(0x38, "store"); a.b(0xAF)
    a.label("store"); a.b(0xEA, 0x00, 0xC1); a.abs(0xCD, "loadmsg")
    a.label("done"); a.label("wait2"); a.b(0xF0, 0x44); a.b(0xFE, 144); a.jr(0x28, "wait2")
    a.abs(0xC3, "main")

    a.label("drawrow"); a.b(0x06, 20)           # 20 letras de hl para de; byte 0 vira espaço (terminador)
    a.label("d1"); a.b(0x2A, 0xB7); a.jr(0x20, "d2"); a.b(0x3E, 0x20)
    a.label("d2"); a.b(0xD6, 0x20, 0x12, 0x13, 0x05); a.jr(0x20, "d1"); a.b(0xC9)

    a.label("loadmsg"); a.b(0xFA, 0x00, 0xC1, 0x47)
    a.b(0x21, MSGS & 0xFF, MSGS >> 8); a.b(0x11, MSG_LEN, 0x00); a.b(0x04, 0x05); a.jr(0x28, "got")
    a.label("mul"); a.b(0x19, 0x05); a.jr(0x20, "mul")
    a.label("got"); a.b(0x11, 0x00, 0xC0); a.b(0x06, MSG_LEN)
    a.label("cpm"); a.b(0x2A, 0x12, 0x13, 0x05); a.jr(0x20, "cpm"); a.b(0xC9)
    code = a.resolve()

    rom = bytearray(0x8000)
    rom[0x100:0x104] = bytes([0x00, 0xC3, 0x50, 0x01])
    logo = bytes.fromhex(
        "CEED6666CC0D000B03730083000C000D0008111F8889000EDCCC6EE6DDDDD999BBBB67636E0EECCCDDDC999FBBB9333E")
    rom[0x104:0x134] = logo
    rom[0x134:0x134 + 10] = b"DIALOGTEST"
    rom[0x147] = 0x00; rom[0x148] = 0x00; rom[0x149] = 0x00; rom[0x14A] = 0x01
    rom[0x150:0x150 + len(code)] = code
    assert 0x150 + len(code) < FONT
    rom[FONT:FONT + len(tiles)] = tiles
    for i, text in enumerate(MESSAGES):
        data = text.encode("ascii").ljust(MSG_LEN, b"\x00")     # o resto do buffer é o terminador do jogo
        rom[MSGS + i * MSG_LEN:MSGS + (i + 1) * MSG_LEN] = data
    checksum = 0
    for i in range(0x134, 0x14D):
        checksum = (checksum - rom[i] - 1) & 0xFF
    rom[0x14D] = checksum
    return bytes(rom), preview


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "dialogtest.gb"
    rom, preview = build()
    open(out, "wb").write(rom)
    if len(sys.argv) > 2:
        preview.resize((preview.width * 4, preview.height * 4), Image.NEAREST).save(sys.argv[2])
    print(f"{out}: {len(rom)} bytes")
