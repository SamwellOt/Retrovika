/*
 *     Copyright (C) 2021  Filippo Scognamiglio
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

#include "vfs.h"

#include <unistd.h>
#include <optional>

#include "vfs/vfs_implementation.h"
#include <sys/stat.h>
#include "../log.h"
#include "../utils/utils.h"

namespace libretrodroid {

const char *VFS::path(struct retro_vfs_file_handle* stream) {
    LOGV("VFS Calling path");
    return retro_vfs_file_get_path_impl(stream);
}

struct retro_vfs_file_handle* VFS::open(const char* path, unsigned int mode, unsigned int hints) {
    LOGV("VFS Calling open: %s %i", path, mode);
    auto result = VFS::getInstance().virtualOpen(path, mode, hints);
    if (result != nullptr) {
        return result;
    }

    return retro_vfs_file_open_impl(path, mode, hints);
}

int VFS::close(struct retro_vfs_file_handle *stream) {
    LOGV("VFS Calling close");
    return retro_vfs_file_close_impl(stream);
}

int64_t VFS::size(struct retro_vfs_file_handle *stream) {
    LOGV("VFS Calling size");
    return retro_vfs_file_size_impl(stream);
}

int64_t VFS::tell(struct retro_vfs_file_handle *stream) {
    LOGV("VFS Calling tell");
    return retro_vfs_file_tell_impl(stream);
}

int64_t VFS::seek(struct retro_vfs_file_handle *stream, int64_t offset, int seek_position) {
    LOGV("VFS Calling seek");
    return retro_vfs_file_seek_impl(stream, offset, seek_position);
}

int64_t VFS::read(struct retro_vfs_file_handle *stream, void *s, uint64_t len) {
    LOGV("VFS Calling read");
    return retro_vfs_file_read_impl(stream, s, len);
}

int64_t VFS::write(struct retro_vfs_file_handle *stream, const void *s, uint64_t len) {
    LOGV("VFS Calling write");
    return retro_vfs_file_write_impl(stream, s, len);
}

int VFS::flush(struct retro_vfs_file_handle *stream) {
    LOGV("VFS Calling flush");
    return retro_vfs_file_flush_impl(stream);
}

int VFS::remove(const char *path) {
    LOGV("VFS Calling remove");
    return retro_vfs_file_remove_impl(path);
}

int VFS::rename(const char *old_path, const char *new_path) {
    LOGV("VFS Calling rename");
    return retro_vfs_file_rename_impl(old_path, new_path);
}

int64_t VFS::truncate(struct retro_vfs_file_handle* stream, int64_t length) {
    LOGV("VFS Calling truncate");
    return retro_vfs_file_truncate_impl(stream, length);
}

int VFS::stat(const char *path, int32_t *size) {
    LOGV("VFS Calling stat: %s", path);
    // Virtual files (SAF) have no path on disk: answer from their descriptor.
    VFSFile* virtualFile = VFS::getInstance().findVirtualFile(path);
    if (virtualFile != nullptr) {
        struct ::stat info {};
        if (fstat(virtualFile->getFD(), &info) != 0) return 0;
        if (size != nullptr) *size = (int32_t) info.st_size;
        return RETRO_VFS_STAT_IS_VALID;
    }
    return retro_vfs_stat_impl(path, size);
}

int VFS::mkdir(const char *dir) {
    return retro_vfs_mkdir_impl(dir);
}

struct retro_vfs_dir_handle* VFS::opendir(const char *dir, bool include_hidden) {
    return retro_vfs_opendir_impl(dir, include_hidden);
}

bool VFS::readdir(struct retro_vfs_dir_handle *dirstream) {
    return retro_vfs_readdir_impl(dirstream);
}

const char* VFS::direntGetName(struct retro_vfs_dir_handle *dirstream) {
    return retro_vfs_dirent_get_name_impl(dirstream);
}

bool VFS::direntIsDir(struct retro_vfs_dir_handle *dirstream) {
    return retro_vfs_dirent_is_dir_impl(dirstream);
}

int VFS::closedir(struct retro_vfs_dir_handle *dirstream) {
    return retro_vfs_closedir_impl(dirstream);
}

retro_vfs_interface * VFS::getInterface() {
    // Static: the core keeps the pointer for as long as it runs (a new one per call leaked).
    static retro_vfs_interface interface {
        /* Introduced in VFS API v1 */
        &VFS::path,
        &VFS::open,
        &VFS::close,
        &VFS::size,
        &VFS::tell,
        &VFS::seek,
        &VFS::read,
        &VFS::write,
        &VFS::flush,
        &VFS::remove,
        &VFS::rename,

        /* Introduced in VFS API v2 */
        &VFS::truncate,

        /* Introduced in VFS API v3 */
        &VFS::stat,
        &VFS::mkdir,
        &VFS::opendir,
        &VFS::readdir,
        &VFS::direntGetName,
        &VFS::direntIsDir,
        &VFS::closedir
    };
    return &interface;
}

void VFS::initialize(std::vector<VFSFile> files) {
    this->virtualFiles = std::move(files);
}

void VFS::deinitialize() {
    virtualFiles.clear();
}

struct retro_vfs_file_handle* VFS::virtualOpen(const char *path, unsigned int mode, unsigned int hints) {
    LOGV("VFS Calling open: %s %i", path, mode);

    VFSFile* virtualFile = findVirtualFile(path);

    if (virtualFile == nullptr) {
        return nullptr;
    }

    LOGD("VFS Performing virtual file open: %s", virtualFile->getFileName().data());

    auto stream = new retro_vfs_file_handle;

    int duplicateFD = dup(virtualFile->getFD());
    FILE* file = fdopen(duplicateFD, "rb");
    size_t size = Utils::getFileSize(file);

    LOGV("VFS Virtual file size: %i", size);

    stream->fd = duplicateFD;
    stream->hints = hints;
    stream->size = size;
    stream->buf = nullptr;
    stream->fp = file;
    stream->orig_path = strdup(virtualFile->getFileName().data());
    stream->mappos = 0;
    stream->mapsize = 0;
    stream->mapped = nullptr;
    stream->scheme = VFS_SCHEME_NONE;

    return stream;
}

VFSFile* VFS::findVirtualFile(const char *path) {
    for (auto& virtualFile : virtualFiles) {
        if (strcmp(path, virtualFile.getFileName().data()) == 0) {
            return &virtualFile;
        }
    }
    return nullptr;
}

} // namespace libretrodroid