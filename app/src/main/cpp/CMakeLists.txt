cmake_minimum_required(VERSION 3.22.1)

project("treechat-llama")

# 设置 C++17 标准并开启极限性能优化
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 针对 ARM64 / 天玑 9300 全大核架构的编译优化标志
add_compile_options(-O3 -fPIC -Wall)

# 查找 Android 核心日志系统库
find_library(
    log-lib
    log
)

find_library(
    android-lib
    android
)

# 编译生成核心推理动态库 libllama.so
add_library(
    llama
    SHARED
    llama-jni.cpp
)

# 链接系统库
target_link_libraries(
    llama
    ${log-lib}
    ${android-lib}
)
