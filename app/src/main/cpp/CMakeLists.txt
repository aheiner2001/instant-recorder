cmake_minimum_required(VERSION 3.22)
project(instantrecorder_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# ggml: CPU backend only, linked statically into our JNI library. Tuned for
# Armv8.2+ with dot-product and fp16 arithmetic (every Tensor-based Pixel).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
add_subdirectory(${WHISPER_DIR}/ggml ${CMAKE_CURRENT_BINARY_DIR}/ggml)

# Same source list as whisper.cpp's own Android example.
add_library(whisper_jni SHARED
    whisper_jni.cpp
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp
)
target_include_directories(whisper_jni PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/src
    ${WHISPER_DIR}/ggml/include
)
target_compile_definitions(whisper_jni PRIVATE GGML_USE_CPU WHISPER_VERSION="1.9.5")
target_compile_options(whisper_jni PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(whisper_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(whisper_jni PRIVATE ggml log android)
