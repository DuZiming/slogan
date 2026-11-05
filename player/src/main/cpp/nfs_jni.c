/* Slogan NFS JNI 薄层（自有代码；libnfs 为 LGPL 动态链接，不修改其源码）。
 * 约定：所有函数不抛 Java 异常——失败返回 0/null/-1，由 Kotlin 侧转错误。
 * NFSv3 的 AUTH_SYS 账密映射不在公开头文件中，v1 使用默认认证（多数家用导出可用）。 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <nfsc/libnfs.h>

/* NFSv3 文件类型（枚举值见 RFC 1813 / libnfs-raw-nfs.h，此处本地定义避免依赖生成头） */
#define SLOGAN_NF3REG 1
#define SLOGAN_NF3DIR 2

#define FIELD_SEP "\x1f"
#define ROW_SEP "\n"

/* context 句柄：nfs_init_context + mount */
JNIEXPORT jlong JNICALL
Java_com_slogan_tv_player_nfs_NfsClient_nativeConnect(
        JNIEnv *env, jobject thiz, jstring host, jstring export_path,
        jstring user, jstring pass) {
    (void) thiz; (void) user; (void) pass;
    const char *c_host = (*env)->GetStringUTFChars(env, host, NULL);
    const char *c_export = (*env)->GetStringUTFChars(env, export_path, NULL);

    struct nfs_context *nfs = nfs_init_context();
    jlong result = 0;
    if (nfs != NULL) {
        if (nfs_mount(nfs, c_host, c_export) == 0) {
            result = (jlong) (intptr_t) nfs;
        } else {
            nfs_destroy_context(nfs);
        }
    }
    if (c_host) (*env)->ReleaseStringUTFChars(env, host, c_host);
    if (c_export) (*env)->ReleaseStringUTFChars(env, export_path, c_export);
    return result;
}

JNIEXPORT void JNICALL
Java_com_slogan_tv_player_nfs_NfsClient_nativeDestroy(JNIEnv *env, jobject thiz, jlong ctx) {
    (void) env; (void) thiz;
    if (ctx != 0) nfs_destroy_context((struct nfs_context *) (intptr_t) ctx);
}

/* 目录列表 → "name<US>isDir(0/1)<US>size<mtime\n..."（UTF-8） */
JNIEXPORT jstring JNICALL
Java_com_slogan_tv_player_nfs_NfsClient_nativeList(
        JNIEnv *env, jobject thiz, jlong ctx, jstring dir) {
    (void) thiz;
    if (ctx == 0) return NULL;
    struct nfs_context *nfs = (struct nfs_context *) (intptr_t) ctx;
    const char *c_dir = (*env)->GetStringUTFChars(env, dir, NULL);
    struct nfsdir *nfsdir = NULL;
    jstring result = NULL;
    if (nfs_opendir(nfs, c_dir, &nfsdir) == 0) {
        struct nfsdirent *ent;
        size_t cap = 4096, len = 0;
        char *buf = malloc(cap);
        buf[0] = '\0';
        while ((ent = nfs_readdir(nfs, nfsdir)) != NULL) {
            if (strcmp(ent->name, ".") == 0 || strcmp(ent->name, "..") == 0) continue;
            int is_dir = (ent->type == SLOGAN_NF3DIR);
            char row[1024];
            int n = snprintf(row, sizeof(row), "%s%s%d%s%llu%s%lld%s",
                             ent->name, FIELD_SEP, is_dir, FIELD_SEP,
                             (unsigned long long) ent->size, FIELD_SEP,
                             (long long) ent->mtime.tv_sec, ROW_SEP);
            if (len + (size_t) n + 1 > cap) {
                cap *= 2;
                buf = realloc(buf, cap);
            }
            memcpy(buf + len, row, (size_t) n + 1);
            len += (size_t) n;
        }
        nfs_closedir(nfs, nfsdir);
        result = (*env)->NewStringUTF(env, buf);
        free(buf);
    }
    (*env)->ReleaseStringUTFChars(env, dir, c_dir);
    return result;
}

/* 文件句柄：nfs_open(O_RDONLY) */
JNIEXPORT jlong JNICALL
Java_com_slogan_tv_player_nfs_NfsClient_nativeOpen(
        JNIEnv *env, jobject thiz, jlong ctx, jstring path) {
    (void) thiz;
    if (ctx == 0) return 0;
    struct nfs_context *nfs = (struct nfs_context *) (intptr_t) ctx;
    const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
    struct nfsfh *fh = NULL;
    jlong result = 0;
    if (nfs_open(nfs, c_path, O_RDONLY, &fh) == 0) {
        result = (jlong) (intptr_t) fh;
    }
    (*env)->ReleaseStringUTFChars(env, path, c_path);
    return result;
}

JNIEXPORT void JNICALL
Java_com_slogan_tv_player_nfs_NfsClient_nativeCloseFile(
        JNIEnv *env, jobject thiz, jlong ctx, jlong handle) {
    (void) env; (void) thiz;
    if (ctx != 0 && handle != 0) {
        nfs_close((struct nfs_context *) (intptr_t) ctx, (struct nfsfh *) (intptr_t) handle);
    }
}

JNIEXPORT jlong JNICALL
Java_com_slogan_tv_player_nfs_NfsFileHandle_nativeSize(
        JNIEnv *env, jobject thiz, jlong ctx, jlong handle) {
    (void) env; (void) thiz;
    if (ctx == 0 || handle == 0) return -1;
    struct nfs_context *nfs = (struct nfs_context *) (intptr_t) ctx;
    struct nfs_stat_64 st;
    struct nfsfh *fh = (struct nfsfh *) (intptr_t) handle;
    if (nfs_fstat64(nfs, fh, &st) != 0) return -1;
    return (jlong) st.nfs_size;
}

/* 从 offset 读满 buf，返回读取字节数；0=EOF/错误 */
JNIEXPORT jint JNICALL
Java_com_slogan_tv_player_nfs_NfsFileHandle_nativeReadAt(
        JNIEnv *env, jobject thiz, jlong ctx, jlong handle, jbyteArray buf, jlong offset) {
    (void) thiz;
    if (ctx == 0 || handle == 0) return -1;
    struct nfs_context *nfs = (struct nfs_context *) (intptr_t) ctx;
    struct nfsfh *fh = (struct nfsfh *) (intptr_t) handle;
    jsize len = (*env)->GetArrayLength(env, buf);
    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    uint64_t new_offset = 0;
    if (nfs_lseek(nfs, fh, (int64_t) offset, SEEK_SET, &new_offset) != 0) {
        (*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);
        return -1;
    }
    int n = nfs_read(nfs, fh, data, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, buf, data, 0);
    return n > 0 ? n : 0;
}
