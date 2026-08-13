cmake_minimum_required(VERSION 3.22.1)

project("mypaint")

add_library(
    mypaint
    SHARED
    mypaint_jni.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    mypaint
    ${log-lib}
)
