cmake_minimum_required(VERSION 3.13.2)
project(retrovika7z C)

# LZMA SDK (domínio público): só o necessário para ler o cabeçalho do 7z e decodificar LZMA/LZMA2.
file(GLOB LZMA_SOURCES lzma/*.c)

add_library(retrovika7z SHARED sevenzip.c ${LZMA_SOURCES})
# _FILE_OFFSET_BITS=64: no armeabi-v7a o off_t tem 32 bits, e fseeko/ftello além de 2 GiB (o cabeçalho
# do 7z fica no fim do arquivo) falhariam em jogos grandes de PS2/GameCube.
target_compile_definitions(retrovika7z PRIVATE Z7_PPMD_SUPPORT _FILE_OFFSET_BITS=64)
target_compile_options(retrovika7z PRIVATE -O2 -Wall -Wno-unused-function)
target_link_options(retrovika7z PRIVATE "-Wl,-z,max-page-size=16384")
