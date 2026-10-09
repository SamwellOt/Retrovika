Chaves e valores de opção que cada núcleo declara (SET_VARIABLES / core options v2), lidos dos binários
arm64-v8a do buildbot em 2026-10-02 (pcsx2, play, mupen64plus_next_gles3, pcsx_rearmed, swanstation, ppsspp, flycast).
CoreOptionsTest confere o que o Systems.kt manda para cada núcleo contra estes arquivos. Ao mudar chaves ou
valores em Systems.kt, atualize o arquivo do núcleo com a lista do binário novo.

Gerados em 2026-10-05 a partir do CÓDIGO-FONTE dos núcleos (tabela de opções no branch que o buildbot compila),
não dos binários: genesis_plus_gx, picodrive, mgba, snes9x2010, gpsp, handy, desmume, dolphin, citra, yabasanshiro.
  - genesis_plus_gx, picodrive, mgba, snes9x2010, gpsp, handy, desmume: libretro_core_options.h (core options v2) do
    master do repositório libretro/<núcleo> (mgba: src/platform/libretro; picodrive: platform/libretro).
  - dolphin: Source/Core/DolphinLibretro/Common/Options.{h,cpp} (libretro/dolphin, master).
  - citra: a tabela retro_variable de src/citra_libretro/citra_libretro.cpp (libretro/citra, master); a lista da
    escala da CPU é a que o código monta (100% (Default), depois 5% a 400% de 5 em 5).
  - yabasanshiro: a tabela retro_variable de yabause/src/libretro/libretro.c (libretro/yabause, branch yabasanshiro).
  A ordem dos valores é a da fonte (o primeiro é o padrão nos núcleos de tabela v1: citra, yabasanshiro). Onde a fonte
  tem #if, entram os valores dos dois ramos. A presença das chaves tocadas pelo Systems.kt foi conferida nos
  binários arm64 do buildbot de 2026-10-05 (strings). Quando o JSON for refeito dos binários, vale o do binário.

armsx2 (2026-10-09): da tabela de opções v2 (kOptionDefinitions) de pcsx2-libretro/Main.cpp do ARMSX2/ARMSX2 (main,
e327f0d), no ramo com Vulkan e OpenGL; as chaves e os valores conferem com as strings "Desc; a|b" (v1) do binário arm64
do buildbot de 2026-10-09. armsx2_bios só lista "auto" aqui: os outros valores são os nomes das BIOS achadas em pcsx2/bios.

Para conferir os JSONs contra binários baixados: tools/check-core-options.py <pasta com *_libretro_android.so> (lista as chaves
ausentes e falha se o Systems.kt usar uma delas).
