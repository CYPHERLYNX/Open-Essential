# whisper.cpp v1.9.5 (pinned) with its own bundled ggml, CPU only, arm64-v8a.
cmake_minimum_required(VERSION 3.22.1)
project(oe_whisper C CXX)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2             OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL             OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH        "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN              OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL              OFF CACHE BOOL "" FORCE)

set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory(${WHISPER_DIR} whisper.cpp)

add_library(oe_whisper SHARED whisper_jni.cpp)
target_compile_features(oe_whisper PRIVATE cxx_std_17)
target_link_libraries(oe_whisper PRIVATE whisper ggml log)
target_link_options(oe_whisper PRIVATE
    -Wl,--exclude-libs,ALL
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384)
