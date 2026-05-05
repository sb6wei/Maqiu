cmake_minimum_required(VERSION 3.20)
project(MaqiuReceiver LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(FFMPEG_ROOT "" CACHE PATH "Path to FFmpeg root")
set(SDL2_ROOT "" CACHE PATH "Path to SDL2 root")

find_path(FFMPEG_INCLUDE_DIR libavcodec/avcodec.h
    PATHS ${FFMPEG_ROOT} $ENV{FFMPEG_ROOT}
    PATH_SUFFIXES include)
find_library(FFMPEG_AVCODEC avcodec
    PATHS ${FFMPEG_ROOT} $ENV{FFMPEG_ROOT}
    PATH_SUFFIXES lib)
find_library(FFMPEG_AVUTIL avutil
    PATHS ${FFMPEG_ROOT} $ENV{FFMPEG_ROOT}
    PATH_SUFFIXES lib)
find_library(FFMPEG_SWSCALE swscale
    PATHS ${FFMPEG_ROOT} $ENV{FFMPEG_ROOT}
    PATH_SUFFIXES lib)

find_path(SDL2_INCLUDE_DIR SDL.h
    PATHS ${SDL2_ROOT} $ENV{SDL2_ROOT}
    PATH_SUFFIXES include/SDL2 include)
find_library(SDL2_LIBRARY SDL2
    PATHS ${SDL2_ROOT} $ENV{SDL2_ROOT}
    PATH_SUFFIXES lib)

add_executable(receiver
    src/main.cpp
    src/receiver.cpp
    src/receiver.h
    src/jitter_buffer.h
    src/frame_queue.h
    src/discovery.cpp
    src/discovery.h
)

target_include_directories(receiver PRIVATE ${FFMPEG_INCLUDE_DIR} ${SDL2_INCLUDE_DIR})
target_link_libraries(receiver PRIVATE ${FFMPEG_AVCODEC} ${FFMPEG_AVUTIL} ${FFMPEG_SWSCALE} ${SDL2_LIBRARY} ws2_32)

if (WIN32)
    target_compile_definitions(receiver PRIVATE NOMINMAX)
endif()
