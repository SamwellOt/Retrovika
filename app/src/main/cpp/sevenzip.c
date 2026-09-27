/*
 * Extração de .7z em código nativo, com o LZMA SDK (domínio público, pasta lzma/).
 *
 * O commons-compress aloca o dicionário do LZMA/LZMA2 no heap Java, e .7z com compressão máxima usam
 * dicionários de 256 MB ou mais: em aparelhos cujo heap para em 256 MB (mesmo com largeHeap) a extração
 * falhava por falta de memória. Aqui o dicionário é memória nativa, fora desse limite.
 *
 * O SzArEx_Extract do SDK decodifica o bloco sólido inteiro na RAM (centenas de MB num jogo de CD);
 * por isso só o cabeçalho usa o SDK, e cada bloco é decodificado aos pedaços, gravando direto nos
 * arquivos. Blocos com mais de um codificador (BCJ2, filtros) ou métodos fora de Copy/LZMA/LZMA2
 * devolvem SZ7_UNSUPPORTED, e o lado Java volta para o commons-compress.
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lzma/Precomp.h"
#include "lzma/7z.h"
#include "lzma/7zCrc.h"
#include "lzma/CpuArch.h"
#include "lzma/Alloc.h"
#include "lzma/LzmaDec.h"
#include "lzma/Lzma2Dec.h"

/* Mesmos valores de SevenZipNative.kt. */
#define SZ7_OK 0
#define SZ7_UNSUPPORTED 1
#define SZ7_MEMORY 2
#define SZ7_DATA 3
#define SZ7_WRITE 4
#define SZ7_OPEN 5

#define k_Copy 0
#define k_LZMA2 0x21
#define k_LZMA 0x30101

#define IN_BUF_SIZE ((size_t)1 << 18)
#define OUT_BUF_SIZE ((size_t)1 << 20)
#define LOOK_BUF_SIZE ((size_t)1 << 18)

/* ---- ILookInStream sobre FILE*, só para o SzArEx_Open ler o cabeçalho ---- */

typedef struct {
    ISeekInStream vt;
    FILE *file;
} FileSeekStream;

static SRes FileSeek_Read(ISeekInStreamPtr pp, void *buf, size_t *size) {
    FileSeekStream *p = Z7_CONTAINER_FROM_VTBL(pp, FileSeekStream, vt);
    if (*size == 0) return SZ_OK;
    *size = fread(buf, 1, *size, p->file);
    return ferror(p->file) ? SZ_ERROR_READ : SZ_OK;
}

static SRes FileSeek_Seek(ISeekInStreamPtr pp, Int64 *pos, ESzSeek origin) {
    FileSeekStream *p = Z7_CONTAINER_FROM_VTBL(pp, FileSeekStream, vt);
    int whence = origin == SZ_SEEK_SET ? SEEK_SET : origin == SZ_SEEK_CUR ? SEEK_CUR : SEEK_END;
    if (fseeko(p->file, (off_t)*pos, whence) != 0) return SZ_ERROR_READ;
    *pos = (Int64)ftello(p->file);
    return SZ_OK;
}

typedef struct {
    FILE *file;
    FileSeekStream seek;
    CLookToRead2 look;
    CSzArEx db;
    int opened;
} Archive;

static int archive_open(Archive *a, const char *path) {
    memset(a, 0, sizeof(*a));
    a->file = fopen(path, "rb");
    if (!a->file) return SZ7_OPEN;
    a->seek.vt.Read = FileSeek_Read;
    a->seek.vt.Seek = FileSeek_Seek;
    a->seek.file = a->file;
    LookToRead2_CreateVTable(&a->look, False);
    a->look.buf = (Byte *)ISzAlloc_Alloc(&g_Alloc, LOOK_BUF_SIZE);
    if (!a->look.buf) return SZ7_MEMORY;
    a->look.bufSize = LOOK_BUF_SIZE;
    a->look.realStream = &a->seek.vt;
    LookToRead2_INIT(&a->look)
    CrcGenerateTable();
    SzArEx_Init(&a->db);
    SRes res = SzArEx_Open(&a->db, &a->look.vt, &g_Alloc, &g_Alloc);
    if (res == SZ_ERROR_MEM) return SZ7_MEMORY;
    if (res == SZ_ERROR_UNSUPPORTED) return SZ7_UNSUPPORTED;
    if (res != SZ_OK) return SZ7_OPEN;
    a->opened = 1;
    return SZ7_OK;
}

static void archive_close(Archive *a) {
    if (a->opened) SzArEx_Free(&a->db, &g_Alloc);
    if (a->look.buf) ISzAlloc_Free(&g_Alloc, a->look.buf);
    if (a->file) fclose(a->file);
}

/* ---- Saída: distribui o fluxo do bloco entre os arquivos, na ordem ---- */

typedef struct {
    const CSzArEx *db;
    const char *const *targets; /* por índice de arquivo; NULL = não extrair */
    UInt32 folder;
    UInt32 index;     /* arquivo atual */
    UInt32 end;       /* primeiro índice depois do bloco */
    UInt64 remaining; /* bytes que faltam no arquivo atual */
    FILE *out;
    UInt32 crc;
    int started;
} Sink;

/* Avança até o próximo arquivo com dados deste bloco (pula vazios e pastas, que não estão no fluxo). */
static int sink_next(Sink *s) {
    if (s->started) s->index++;
    s->started = 1;
    while (s->index < s->end && s->db->FileToFolder[s->index] != s->folder) s->index++;
    if (s->index >= s->end) return SZ7_OK;
    s->remaining = SzArEx_GetFileSize(s->db, s->index);
    s->crc = CRC_INIT_VAL;
    s->out = NULL;
    const char *path = s->targets[s->index];
    if (path) {
        s->out = fopen(path, "wb");
        if (!s->out) return SZ7_WRITE;
    }
    return SZ7_OK;
}

static int sink_finish_file(Sink *s) {
    if (s->out) {
        int failed = fclose(s->out) != 0;
        s->out = NULL;
        if (failed) return SZ7_WRITE;
        if (SzBitWithVals_Check(&s->db->CRCs, s->index) && CRC_GET_DIGEST(s->crc) != s->db->CRCs.Vals[s->index])
            return SZ7_DATA;
    }
    return SZ7_OK;
}

static int sink_write(Sink *s, const Byte *data, size_t size) {
    while (size > 0) {
        while (s->remaining == 0) {
            if (s->index >= s->end) return SZ7_DATA; /* mais dados do que os arquivos somam */
            int r = sink_finish_file(s);
            if (r == SZ7_OK) r = sink_next(s);
            if (r != SZ7_OK) return r;
            if (s->index >= s->end) return SZ7_DATA;
        }
        size_t chunk = size < s->remaining ? size : (size_t)s->remaining;
        if (s->out) {
            if (fwrite(data, 1, chunk, s->out) != chunk) return SZ7_WRITE;
            s->crc = CrcUpdate(s->crc, data, chunk);
        }
        s->remaining -= chunk;
        data += chunk;
        size -= chunk;
    }
    return SZ7_OK;
}

/* ---- Decodificação de um bloco ---- */

/* Menor dicionário que ainda cobre a saída inteira: distâncias nunca passam do que já foi escrito. */
static UInt32 capped_dict(UInt32 dict, UInt64 unpackSize) {
    UInt64 min = unpackSize < 4096 ? 4096 : unpackSize;
    return (UInt64)dict > min ? (UInt32)min : dict;
}

static UInt32 lzma2_dict_of(Byte prop) {
    return prop >= 40 ? 0xFFFFFFFF : (((UInt32)2 | (prop & 1)) << (prop / 2 + 11));
}

static Byte lzma2_prop_for(UInt32 dict) {
    Byte p = 0;
    while (p < 40 && lzma2_dict_of(p) < dict) p++;
    return p;
}

/* Só um codificador Copy/LZMA/LZMA2 por bloco; o resto fica com o commons-compress. */
static int folder_supported(const CSzArEx *db, UInt32 f) {
    CSzFolder folder;
    CSzData sd;
    sd.Data = db->db.CodersData + db->db.FoCodersOffsets[f];
    sd.Size = db->db.FoCodersOffsets[f + 1] - db->db.FoCodersOffsets[f];
    if (SzGetNextFolderItem(&folder, &sd) != SZ_OK) return 0;
    if (folder.NumCoders != 1 || folder.NumPackStreams != 1) return 0;
    UInt32 m = folder.Coders[0].MethodID;
    return m == k_Copy || m == k_LZMA || m == k_LZMA2;
}

static int folder_wanted(const CSzArEx *db, UInt32 f, const char *const *targets) {
    for (UInt32 i = db->FolderToFile[f]; i < db->FolderToFile[f + 1]; i++)
        if (db->FileToFolder[i] == f && targets[i]) return 1;
    return 0;
}

static int decode_folder(Archive *a, UInt32 f, const char *const *targets) {
    const CSzArEx *db = &a->db;
    UInt32 first = db->FolderToFile[f], end = db->FolderToFile[f + 1];
    UInt32 lastWanted = (UInt32)-1;
    for (UInt32 i = first; i < end; i++)
        if (db->FileToFolder[i] == f && targets[i]) lastWanted = i;
    if (lastWanted == (UInt32)-1) return SZ7_OK;

    CSzFolder folder;
    CSzData sd;
    const Byte *data = db->db.CodersData + db->db.FoCodersOffsets[f];
    sd.Data = data;
    sd.Size = db->db.FoCodersOffsets[f + 1] - db->db.FoCodersOffsets[f];
    if (SzGetNextFolderItem(&folder, &sd) != SZ_OK) return SZ7_DATA;
    if (folder.NumCoders != 1 || folder.NumPackStreams != 1) return SZ7_UNSUPPORTED;
    const CSzCoderInfo *coder = &folder.Coders[0];
    if (coder->MethodID != k_Copy && coder->MethodID != k_LZMA && coder->MethodID != k_LZMA2) return SZ7_UNSUPPORTED;

    UInt64 unpackSize = SzAr_GetFolderUnpackSize(&db->db, f);
    UInt32 packIndex = db->db.FoStartPackStreamIndex[f];
    UInt64 packPos = db->dataPos + db->db.PackPositions[packIndex];
    UInt64 packRemaining = db->db.PackPositions[packIndex + 1] - db->db.PackPositions[packIndex];
    if (fseeko(a->file, (off_t)packPos, SEEK_SET) != 0) return SZ7_DATA;

    const Byte *props = data + coder->PropsOffset;
    CLzmaDec lzma;
    CLzma2Dec lzma2;
    LzmaDec_CONSTRUCT(&lzma)
    Lzma2Dec_CONSTRUCT(&lzma2)
    int result = SZ7_OK;

    if (coder->MethodID == k_LZMA) {
        if (coder->PropsSize != LZMA_PROPS_SIZE) return SZ7_UNSUPPORTED;
        Byte p[LZMA_PROPS_SIZE];
        memcpy(p, props, LZMA_PROPS_SIZE);
        UInt32 dict = GetUi32(p + 1);
        SetUi32(p + 1, capped_dict(dict, unpackSize));
        SRes r = LzmaDec_Allocate(&lzma, p, LZMA_PROPS_SIZE, &g_Alloc);
        if (r == SZ_ERROR_MEM) return SZ7_MEMORY;
        if (r != SZ_OK) return SZ7_UNSUPPORTED;
        LzmaDec_Init(&lzma);
    } else if (coder->MethodID == k_LZMA2) {
        if (coder->PropsSize != 1 || props[0] > 40) return SZ7_UNSUPPORTED;
        Byte p = props[0];
        UInt32 capped = capped_dict(lzma2_dict_of(p), unpackSize);
        Byte smaller = lzma2_prop_for(capped);
        if (smaller < p) p = smaller;
        SRes r = Lzma2Dec_Allocate(&lzma2, p, &g_Alloc);
        if (r == SZ_ERROR_MEM) return SZ7_MEMORY;
        if (r != SZ_OK) return SZ7_UNSUPPORTED;
        Lzma2Dec_Init(&lzma2);
    }

    Byte *inBuf = (Byte *)malloc(IN_BUF_SIZE);
    Byte *outBuf = (Byte *)malloc(OUT_BUF_SIZE);
    Sink sink = { db, targets, f, first, end, 0, NULL, 0, 0 };
    if (!inBuf || !outBuf) { result = SZ7_MEMORY; goto done; }
    result = sink_next(&sink);
    if (result != SZ7_OK) goto done;

    size_t inPos = 0, inSize = 0;
    UInt64 unpackRemaining = unpackSize;
    UInt32 folderCrc = CRC_INIT_VAL;
    int checkFolderCrc = SzBitWithVals_Check(&db->db.FolderCRCs, f);
    while (unpackRemaining > 0) {
        if (inPos == inSize && packRemaining > 0) {
            size_t want = packRemaining < IN_BUF_SIZE ? (size_t)packRemaining : IN_BUF_SIZE;
            inSize = fread(inBuf, 1, want, a->file);
            if (inSize == 0) { result = SZ7_DATA; break; }
            packRemaining -= inSize;
            inPos = 0;
        }
        SizeT outProcessed = unpackRemaining < OUT_BUF_SIZE ? (SizeT)unpackRemaining : OUT_BUF_SIZE;
        SizeT inProcessed = inSize - inPos;
        ELzmaStatus status;
        SRes r = SZ_OK;
        if (coder->MethodID == k_Copy) {
            if (outProcessed > inProcessed) outProcessed = inProcessed;
            memcpy(outBuf, inBuf + inPos, outProcessed);
            inProcessed = outProcessed;
        } else if (coder->MethodID == k_LZMA) {
            r = LzmaDec_DecodeToBuf(&lzma, outBuf, &outProcessed, inBuf + inPos, &inProcessed, LZMA_FINISH_ANY, &status);
        } else {
            r = Lzma2Dec_DecodeToBuf(&lzma2, outBuf, &outProcessed, inBuf + inPos, &inProcessed, LZMA_FINISH_ANY, &status);
        }
        inPos += inProcessed;
        if (r != SZ_OK) { result = r == SZ_ERROR_MEM ? SZ7_MEMORY : SZ7_DATA; break; }
        if (outProcessed == 0 && inProcessed == 0) { result = SZ7_DATA; break; }
        unpackRemaining -= outProcessed;
        if (checkFolderCrc) folderCrc = CrcUpdate(folderCrc, outBuf, outProcessed);
        result = sink_write(&sink, outBuf, outProcessed);
        if (result != SZ7_OK) break;
        /* Com o que foi pedido já gravado, o resto do bloco sólido não precisa ser decodificado. */
        if (!checkFolderCrc && sink.index == lastWanted && sink.remaining == 0) break;
    }
    if (result == SZ7_OK) result = sink_finish_file(&sink);
    if (result == SZ7_OK && unpackRemaining == 0 && checkFolderCrc &&
        CRC_GET_DIGEST(folderCrc) != db->db.FolderCRCs.Vals[f])
        result = SZ7_DATA;

done:
    if (sink.out) fclose(sink.out);
    free(inBuf);
    free(outBuf);
    LzmaDec_Free(&lzma, &g_Alloc);
    Lzma2Dec_Free(&lzma2, &g_Alloc);
    return result;
}

/* ---- JNI ---- */

/** Nomes das entradas na ordem do arquivo; pastas terminam em "/". Null se não abrir. */
JNIEXPORT jobjectArray JNICALL
Java_com_retrovika_app_core_storage_SevenZipNative_list(JNIEnv *env, jclass clazz, jstring jpath) {
    (void)clazz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    Archive a;
    int r = archive_open(&a, path);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    jobjectArray names = NULL;
    if (r == SZ7_OK) {
        jclass stringClass = (*env)->FindClass(env, "java/lang/String");
        names = (*env)->NewObjectArray(env, (jsize)a.db.NumFiles, stringClass, NULL);
        UInt16 *buf = NULL;
        size_t bufLen = 0;
        for (UInt32 i = 0; names && i < a.db.NumFiles; i++) {
            size_t len = SzArEx_GetFileNameUtf16(&a.db, i, NULL);
            if (len + 1 > bufLen) {
                free(buf);
                bufLen = len + 1;
                buf = (UInt16 *)malloc(bufLen * sizeof(UInt16));
                if (!buf) { names = NULL; break; }
            }
            SzArEx_GetFileNameUtf16(&a.db, i, buf);
            size_t n = len > 0 ? len - 1 : 0; /* sem o terminador */
            if (SzArEx_IsDir(&a.db, i)) buf[n++] = '/';
            jstring s = (*env)->NewString(env, (const jchar *)buf, (jsize)n);
            (*env)->SetObjectArrayElement(env, names, (jsize)i, s);
            (*env)->DeleteLocalRef(env, s);
        }
        free(buf);
    }
    archive_close(&a);
    return names;
}

/** Extrai as entradas cujo destino em [jtargets] (mesmo índice de list) não é null. Devolve um SZ7_*. */
JNIEXPORT jint JNICALL
Java_com_retrovika_app_core_storage_SevenZipNative_extract(JNIEnv *env, jclass clazz, jstring jpath, jobjectArray jtargets) {
    (void)clazz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    Archive a;
    int r = archive_open(&a, path);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (r != SZ7_OK) { archive_close(&a); return r; }

    UInt32 n = a.db.NumFiles;
    if ((UInt32)(*env)->GetArrayLength(env, jtargets) != n) { archive_close(&a); return SZ7_UNSUPPORTED; }
    const char **targets = (const char **)calloc(n ? n : 1, sizeof(char *));
    jstring *refs = (jstring *)calloc(n ? n : 1, sizeof(jstring));
    if (!targets || !refs) { free(targets); free(refs); archive_close(&a); return SZ7_MEMORY; }
    for (UInt32 i = 0; i < n; i++) {
        refs[i] = (jstring)(*env)->GetObjectArrayElement(env, jtargets, (jsize)i);
        if (refs[i]) targets[i] = (*env)->GetStringUTFChars(env, refs[i], NULL);
    }

    /* Recusa antes de gravar qualquer coisa: o commons-compress refaz tudo do zero. */
    for (UInt32 f = 0; r == SZ7_OK && f < a.db.db.NumFolders; f++)
        if (folder_wanted(&a.db, f, targets) && !folder_supported(&a.db, f)) r = SZ7_UNSUPPORTED;

    /* Arquivos vazios não estão em nenhum bloco: basta criá-los. */
    for (UInt32 i = 0; r == SZ7_OK && i < n; i++) {
        if (targets[i] && !SzArEx_IsDir(&a.db, i) && a.db.FileToFolder[i] == (UInt32)-1) {
            FILE *out = fopen(targets[i], "wb");
            if (!out || fclose(out) != 0) r = SZ7_WRITE;
        }
    }
    for (UInt32 f = 0; r == SZ7_OK && f < a.db.db.NumFolders; f++) r = decode_folder(&a, f, targets);

    for (UInt32 i = 0; i < n; i++) {
        if (refs[i]) {
            (*env)->ReleaseStringUTFChars(env, refs[i], targets[i]);
            (*env)->DeleteLocalRef(env, refs[i]);
        }
    }
    free(targets);
    free(refs);
    archive_close(&a);
    return r;
}
