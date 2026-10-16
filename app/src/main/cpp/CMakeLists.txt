
cmake_minimum_required(VERSION 3.22.1)

project(novaplayer_native LANGUAGES CXX)

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

find_library(
    log_lib
    log
)

target_link_libraries(
    nova_native
    PRIVATE
    ${log_lib}
)
