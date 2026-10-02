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

#include <cstdio>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>
#include <unistd.h>

#include "utils.h"
#include "../log.h"

namespace libretrodroid {

std::vector<int8_t> Utils::readFileAsBytes(const std::string &filePath) {
    std::ifstream fileStream(filePath, std::ios::binary);
    if (!fileStream) {
        LOGE("Cannot open the game file");
        throw std::runtime_error("Cannot open the game file");
    }
    fileStream.seekg(0, std::ios::end);
    std::streamoff size = fileStream.tellg();
    // tellg devolve -1 na falha: virava um new char[SIZE_MAX].
    if (size < 0) {
        LOGE("Cannot read the game file size");
        throw std::runtime_error("Cannot read the game file size");
    }
    std::vector<int8_t> bytes((size_t) size);
    fileStream.seekg(0, std::ios::beg);
    if (size > 0 && !fileStream.read(reinterpret_cast<char*>(bytes.data()), size)) {
        LOGE("Cannot read the game file");
        throw std::runtime_error("Cannot read the game file");
    }
    return bytes;
}

std::vector<int8_t> Utils::readFileAsBytes(int fileDescriptor) {
    // Lê por uma cópia: o descritor original é do VFSFile, que o fecha (antes era fechado aqui também, e o
    // segundo close podia acertar um descritor já reaproveitado por outra thread).
    int duplicate = dup(fileDescriptor);
    FILE* file = duplicate >= 0 ? fdopen(duplicate, "rb") : nullptr;
    if (file == nullptr) {
        if (duplicate >= 0) close(duplicate);
        LOGE("Cannot open the game file descriptor");
        throw std::runtime_error("Cannot open the game file descriptor");
    }
    // Daqui em diante o fclose fecha a cópia.
    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        throw std::runtime_error("Cannot read the game file size");
    }
    long size = ftell(file);
    if (size < 0 || fseek(file, 0, SEEK_SET) != 0) {
        fclose(file);
        LOGE("Cannot read the game file size");
        throw std::runtime_error("Cannot read the game file size");
    }
    std::vector<int8_t> bytes((size_t) size);
    size_t read = size > 0 ? fread(bytes.data(), 1, (size_t) size, file) : 0;
    fclose(file);
    if (read != (size_t) size) {
        LOGE("Cannot read the game file");
        throw std::runtime_error("Cannot read the game file");
    }
    return bytes;
}

size_t Utils::getFileSize(FILE* file) {
    if (file == nullptr) return 0;
    fseek(file, 0, SEEK_SET);
    fseek(file, 0, SEEK_END);
    long size = ftell(file);
    fseek(file, 0, SEEK_SET);
    return size < 0 ? 0 : (size_t) size;
}

} //namespace libretrodroid
