cmake_minimum_required(VERSION 3.22.1)
project("cpufeatures")

add_library(cpufeatures SHARED CpuFeatures.cpp)

find_library(log-lib log)

target_link_libraries(cpufeatures ${log-lib})
