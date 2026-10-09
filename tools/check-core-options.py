#!/usr/bin/env python3
"""Confere as chaves de app/src/test/resources/core-options/*.json contra os binários dos núcleos.

Uso: tools/check-core-options.py <pasta com *_libretro_android.so> [<outra pasta> ...]

Para cada núcleo com JSON, usa o binário mais novo achado nas pastas e lista as chaves que não aparecem
nas strings dele. Chave ausente não é erro por si só (opção sob #if, build antiga), mas se for uma chave
que o Systems.kt usa, o CoreOptionsTest passa e o núcleo ignora a opção: por isso o script também diz
quais ausentes o Systems.kt menciona. Saída com código 1 nesse caso.
"""
import glob
import json
import os
import re
import subprocess
import sys

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JSONS = os.path.join(RAIZ, "app/src/test/resources/core-options")
SYSTEMS = os.path.join(RAIZ, "app/src/main/java/com/retrovika/app/core/systems/Systems.kt")


def main(pastas):
    if not pastas:
        sys.exit(__doc__)
    melhor = {}
    for pasta in pastas:
        for p in glob.glob(os.path.join(pasta, "**", "*_libretro_android.so"), recursive=True):
            nome = os.path.basename(p).replace("_libretro_android.so", "")
            if nome not in melhor or os.path.getmtime(p) > os.path.getmtime(melhor[nome]):
                melhor[nome] = p
    systems = open(SYSTEMS, encoding="utf-8").read()
    usadas_ausentes = 0
    for jf in sorted(glob.glob(os.path.join(JSONS, "*.json"))):
        nucleo = os.path.basename(jf)[:-5]
        chaves = list(json.load(open(jf, encoding="utf-8")))
        binario = melhor.get(nucleo)
        if not binario:
            print(f"{nucleo:26} sem binário")
            continue
        saida = subprocess.run(["strings", "-n", "4", binario], capture_output=True, text=True).stdout
        tokens = set(re.findall(r"[A-Za-z0-9_-]+", saida))
        ausentes = [k for k in chaves if k not in tokens]
        usadas = [k for k in ausentes if f'"{k}"' in systems]
        usadas_ausentes += len(usadas)
        print(f"{nucleo:26} {len(chaves):3} chaves, ausentes: {len(ausentes)}"
              + (f", USADAS pelo Systems.kt: {usadas}" if usadas else ""))
    sys.exit(1 if usadas_ausentes else 0)


main(sys.argv[1:])
