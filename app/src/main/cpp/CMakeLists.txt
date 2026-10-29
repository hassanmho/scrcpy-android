cmake_minimum_required(VERSION 3.18)
project(adb_pairing C CXX)

# Build BoringSSL without assembly (avoids Go/Perl requirement)
set(OPENSSL_NO_ASM ON CACHE BOOL "" FORCE)

# Use the boringssl submodule when it is checked out; otherwise (ZIP download,
# clone without --recurse-submodules) fetch the same pinned commit.
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/boringssl/CMakeLists.txt")
    set(BORINGSSL_DIR "${CMAKE_CURRENT_SOURCE_DIR}/boringssl")
    add_subdirectory(boringssl EXCLUDE_FROM_ALL)
else()
    include(FetchContent)
    FetchContent_Declare(
        boringssl
        GIT_REPOSITORY https://github.com/google/boringssl.git
        GIT_TAG        a2f9a2474d0703e6ac3111a07129ead7c5aa8245
        GIT_SHALLOW    FALSE
    )
    FetchContent_GetProperties(boringssl)
    if(NOT boringssl_POPULATED)
        FetchContent_Populate(boringssl)
        add_subdirectory(${boringssl_SOURCE_DIR} ${boringssl_BINARY_DIR} EXCLUDE_FROM_ALL)
    endif()
    set(BORINGSSL_DIR "${boringssl_SOURCE_DIR}")
endif()

add_library(adb_pairing SHARED adb_pairing_jni.cpp)

target_include_directories(adb_pairing PRIVATE ${BORINGSSL_DIR}/include)

find_library(log-lib log)
target_link_libraries(adb_pairing crypto ssl ${log-lib})
