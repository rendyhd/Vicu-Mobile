# Vicu's local speech engine (vicu_speech.h) over whisper.cpp, pinned to a release and checked by
# SHA-256. Built by the Android app (externalNativeBuild), the iOS script
# (iosApp/scripts/build-speech.sh) and the desktop script (scripts/build-speech.mjs).
#
#   -DVICU_SPEECH_SHARED=ON|OFF     a shared library (Android, desktop) or a static one (iOS)
#   -DVICU_WHISPER_SOURCE_DIR=path  an unpacked whisper.cpp to use instead of downloading it
cmake_minimum_required(VERSION 3.22)
project(vicu_speech C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(VICU_SPEECH_SHARED "Build vicu_speech as a shared library" ON)

set(VICU_WHISPER_VERSION "1.9.5")
set(VICU_WHISPER_SHA256 "ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720")

# whisper.cpp and ggml go inside vicu_speech as static code.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
if(APPLE)
    # The Metal shaders are embedded as source and compiled on the device, so the build needs no
    # Metal toolchain.
    set(GGML_METAL_EMBED_LIBRARY ON CACHE BOOL "" FORCE)
endif()

include(FetchContent)
if(VICU_WHISPER_SOURCE_DIR)
    FetchContent_Declare(whisper SOURCE_DIR "${VICU_WHISPER_SOURCE_DIR}")
else()
    # DOWNLOAD_EXTRACT_TIMESTAMP is CMake 3.24 and later; older versions (the Android SDK's 3.22.1)
    # would read it as part of URL_HASH.
    set(VICU_WHISPER_TIMESTAMP)
    if(CMAKE_VERSION VERSION_GREATER_EQUAL 3.24)
        set(VICU_WHISPER_TIMESTAMP DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
    endif()
    FetchContent_Declare(whisper
        URL "https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v${VICU_WHISPER_VERSION}.tar.gz"
        URL_HASH "SHA256=${VICU_WHISPER_SHA256}"
        ${VICU_WHISPER_TIMESTAMP})
endif()
FetchContent_MakeAvailable(whisper)

if(VICU_SPEECH_SHARED)
    add_library(vicu_speech SHARED vicu_speech.cpp)
else()
    add_library(vicu_speech STATIC vicu_speech.cpp)
endif()
target_include_directories(vicu_speech PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(vicu_speech PRIVATE whisper parakeet)
set_target_properties(vicu_speech PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)

if(ANDROID)
    target_sources(vicu_speech PRIVATE vicu_speech_jni.cpp)
    find_library(LOG_LIB log)
    target_link_libraries(vicu_speech PRIVATE ${LOG_LIB} android)
    # Android 15 and later run on 16 KB pages.
    target_link_options(vicu_speech PRIVATE "-Wl,-z,max-page-size=16384")
endif()
