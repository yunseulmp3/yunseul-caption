cmake_minimum_required(VERSION 3.18)
project(yunseulcaption C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# whisper.cpp 소스 위치 (GitHub Actions가 빌드 전에 이 폴더로 clone 함)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp 소스가 없음: ${WHISPER_DIR} (README의 빌드 방법 참고)")
endif()

# 정적 라이브러리로 묶어서 .so 하나로 배포
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    # 2019년 이후 갤럭시 전 기종(ARMv8.2) 공통 — dotprod/fp16 가속
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(${WHISPER_DIR} whisper-build EXCLUDE_FROM_ALL)

add_library(yunseulcaption SHARED native-lib.cpp)
target_link_libraries(yunseulcaption PRIVATE whisper)
if(ANDROID)
    target_link_libraries(yunseulcaption PRIVATE log android)
    target_link_options(yunseulcaption PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
endif()
