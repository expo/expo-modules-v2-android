cmake_minimum_required(VERSION 3.18)
project(expo-modules-cpp-tests CXX)

# Tests for the C++ API (api/src/main/cpp/expo-modules-cpp). They run real JavaScript on a real
# Hermes, with no JVM: the API is JNI-free, so nothing else is needed. Configure with the same
# dependencies the Gradle build unpacks for the bridge, plus the Hermes headers:
#
#   cmake -S api/src/test/cpp -B build-native/cpp-tests -G Ninja \
#     -DJSI_DIR=$PWD/api/build/hermes-env-jsi \
#     -DHERMES_ENV_LIBS_DIR=$PWD/api/build/hermes-env-libs \
#     -DHERMES_API_DIR=$PWD/../hermes-tests-environment/third-party/hermes
#   cmake --build build-native/cpp-tests && ctest --test-dir build-native/cpp-tests --output-on-failure
#
# HERMES_API_DIR is a Hermes checkout: the host artifact ships JSI's headers but not
# <hermes/hermes.h>, which the tests need to make a runtime. That is also why this is not a
# part of the Gradle build.
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(MAIN_CPP_DIR "${CMAKE_CURRENT_LIST_DIR}/../../main/cpp")

foreach (VARIABLE JSI_DIR HERMES_ENV_LIBS_DIR HERMES_API_DIR)
  if (NOT DEFINED ${VARIABLE})
    message(FATAL_ERROR "Pass -D${VARIABLE}=<path>; see the comment at the top of this file.")
  endif ()
endforeach ()

add_library(jsi SHARED IMPORTED)
set_target_properties(jsi PROPERTIES
        IMPORTED_LOCATION ${HERMES_ENV_LIBS_DIR}/libhermes-test-env${CMAKE_SHARED_LIBRARY_SUFFIX})
target_include_directories(jsi INTERFACE ${JSI_DIR})

add_subdirectory(${MAIN_CPP_DIR}/expo-jsi expo-jsi)
add_subdirectory(${MAIN_CPP_DIR}/expo-modules-cpp expo-modules-cpp)

file(GLOB TEST_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_LIST_DIR}/*.cpp")
add_executable(expo-modules-cpp-tests ${TEST_SOURCES})
target_link_libraries(expo-modules-cpp-tests PRIVATE expo-modules-cpp)
target_include_directories(expo-modules-cpp-tests PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}
        ${HERMES_API_DIR}/API
        ${HERMES_API_DIR}/public)
target_compile_options(expo-modules-cpp-tests PRIVATE -Wall -Wextra -Wpedantic -Werror)
set_target_properties(expo-modules-cpp-tests PROPERTIES BUILD_RPATH ${HERMES_ENV_LIBS_DIR})

option(EXPO_MODULES_CPP_SANITIZE "Build the tests with AddressSanitizer and UBSan" OFF)
if (EXPO_MODULES_CPP_SANITIZE)
  target_compile_options(expo-modules-cpp PRIVATE -fsanitize=address,undefined -fno-sanitize=vptr -fno-omit-frame-pointer)
  target_compile_options(expo-modules-cpp-tests PRIVATE -fsanitize=address,undefined -fno-sanitize=vptr -fno-omit-frame-pointer)
  target_link_options(expo-modules-cpp-tests PRIVATE -fsanitize=address,undefined -fno-sanitize=vptr)
endif ()

# What the C++ API costs over a hand-written host function. Not a test: run it from a Release build.
add_executable(expo-modules-cpp-benchmark EXCLUDE_FROM_ALL benchmark/Benchmark.cpp)
target_link_libraries(expo-modules-cpp-benchmark PRIVATE expo-modules-cpp)
target_include_directories(expo-modules-cpp-benchmark PRIVATE ${HERMES_API_DIR}/API ${HERMES_API_DIR}/public)
target_compile_options(expo-modules-cpp-benchmark PRIVATE -Wall -Wextra -Wpedantic -Werror)
set_target_properties(expo-modules-cpp-benchmark PROPERTIES BUILD_RPATH ${HERMES_ENV_LIBS_DIR})

enable_testing()
add_test(NAME expo-modules-cpp-tests COMMAND expo-modules-cpp-tests)
