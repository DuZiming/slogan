cmake_minimum_required(VERSION 3.22)
project(slogan-native)

# ---- slogan-nfs.so（libnfs JNI 薄层）----
add_library(slogan-nfs SHARED nfs_jni.c)
target_include_directories(slogan-nfs PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/libnfs-include)
set(LIBNFS_SO ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnfs.so)
if(NOT EXISTS ${LIBNFS_SO})
    message(FATAL_ERROR "missing prebuilt libnfs.so for ${ANDROID_ABI}; run tools/build-libnfs.ps1 first")
endif()
find_library(log-lib log)
target_link_libraries(slogan-nfs ${LIBNFS_SO} ${log-lib})

# ---- slogan-mpv.so（自有 libmpv JNI + EGL 渲染桥）----
add_library(slogan-mpv SHARED slogan_mpv_jni.c)
target_include_directories(slogan-mpv PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/mpv-include)
set(LIBMPV_SO ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmpv.so)
if(NOT EXISTS ${LIBMPV_SO})
    message(FATAL_ERROR "missing prebuilt libmpv.so for ${ANDROID_ABI}; run tools/build-libmpv-lgpl.sh in WSL first")
endif()
target_link_libraries(slogan-mpv ${LIBMPV_SO} EGL android ${log-lib})
