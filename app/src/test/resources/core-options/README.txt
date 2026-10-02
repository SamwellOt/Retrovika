Chaves e valores de opção que cada núcleo declara (SET_VARIABLES / core options v2), lidos dos binários
arm64-v8a do buildbot em 2026-10-02 (pcsx2, play, mupen64plus_next_gles3, pcsx_rearmed, swanstation, ppsspp, flycast).
CoreOptionsTest confere o que o Systems.kt manda para cada núcleo contra estes arquivos. Ao mudar chaves ou
valores em Systems.kt, atualize o arquivo do núcleo com a lista do binário novo.
