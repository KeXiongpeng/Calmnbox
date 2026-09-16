cmake_minimum_required(VERSION 3.22.1)
project(calm_mnn LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(MNN_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../libs/mnn-android)

add_library(calm_mnn SHARED calm_llm_jni.cpp)

target_include_directories(calm_mnn PRIVATE ${MNN_ROOT}/include)

find_library(log-lib log)

target_link_libraries(calm_mnn
        ${MNN_ROOT}/libs/arm64-v8a/libMNN.so
        ${MNN_ROOT}/libs/arm64-v8a/libMNN_CL.so
        ${MNN_ROOT}/libs/arm64-v8a/libMNN_Express.so
        ${MNN_ROOT}/libs/arm64-v8a/libMNNOpenCV.so
        ${MNN_ROOT}/libs/arm64-v8a/libMNNAudio.so
        ${MNN_ROOT}/libs/arm64-v8a/libMNN_Vulkan.so
        ${MNN_ROOT}/libs/arm64-v8a/libmnncore.so
        ${MNN_ROOT}/libs/arm64-v8a/libllm.so
        ${log-lib})
