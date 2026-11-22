# On-device text and vision engine: llama.cpp + libmtmd (pinned, see third_party/ENGINES.md),
# built for arm64-v8a, CPU only. Everything is linked statically into one library whose only
# exported symbols are its JNI functions, so its ggml can't clash with whisper's (oe_whisper).
cmake_minimum_required(VERSION 3.22.1)
project(oe_llama C CXX)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/llama.cpp)

set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD      ON  CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS      OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO            OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
# ARMv8.2 with dot product and half precision; the app checks the phone has both before loading.
set(GGML_CPU_ARM_ARCH     "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN           OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        ON  CACHE BOOL "" FORCE)

# Hide every symbol of the vendored code.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory(${LLAMA_DIR} llama.cpp)

add_library(oe_llama SHARED llama_jni.cpp)
target_compile_features(oe_llama PRIVATE cxx_std_17)
target_link_libraries(oe_llama PRIVATE mtmd llama ggml log android)
target_link_options(oe_llama PRIVATE
    -Wl,--exclude-libs,ALL
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384)
