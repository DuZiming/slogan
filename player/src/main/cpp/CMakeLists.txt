cmake_minimum_required(VERSION 3.22)
project(slogan-nfs)

add_library(slogan-nfs SHARED nfs_jni.c)

# libnfs 头文件随仓库携带（上游 commit 见 jniLibs/libnfs-version.txt；.so 由 tools/build-libnfs.ps1 预编译）
target_include_directories(slogan-nfs PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/libnfs-include)

# 链接预编译的 libnfs.so（jniLibs 按 ABI 就位）
set(LIBNFS_SO ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnfs.so)
if(NOT EXISTS ${LIBNFS_SO})
    message(FATAL_ERROR "missing prebuilt libnfs.so for ${ANDROID_ABI}; run tools/build-libnfs.ps1 first")
endif()

find_library(log-lib log)
target_link_libraries(slogan-nfs ${LIBNFS_SO} ${log-lib})
