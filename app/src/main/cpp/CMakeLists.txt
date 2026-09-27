cmake_minimum_required(VERSION 3.13.2)
project(retrovika7z C)

# LZMA SDK (domínio público): só o necessário para ler o cabeçalho do 7z e decodificar LZMA/LZMA2.
file(GLOB LZMA_SOURCES lzma/*.c)

add_library(retrovika7z SHARED sevenzip.c ${LZMA_SOURCES})
target_compile_definitions(retrovika7z PRIVATE Z7_PPMD_SUPPORT)
target_compile_options(retrovika7z PRIVATE -O2 -Wall -Wno-unused-function)
target_link_options(retrovika7z PRIVATE "-Wl,-z,max-page-size=16384")
