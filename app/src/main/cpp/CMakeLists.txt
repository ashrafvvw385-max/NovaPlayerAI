
cmake_minimum_required(VERSION 3.22.1)

project(novaplayer_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

set(WHISPER_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp"
)

if(NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "Whisper.cpp source files are missing"
    )
endif()

add_subdirectory(
    "${WHISPER_DIR}"
    "${CMAKE_CURRENT_BINARY_DIR}/whisper"
)

add_library(
    nova_native
    SHARED
    whisper_bridge.cpp
)

target_compile_features(
    nova_native
    PRIVATE
    cxx_std_17
)

target_include_directories(
    nova_native
    PRIVATE
    "${WHISPER_DIR}/include"
)

find_library(
    android_log
    log
)

target_link_libraries(
    nova_native
    PRIVATE
    whisper
    ${android_log}
)
