cmake_minimum_required(VERSION 3.18)
project(expo-test-support CXX)

# Paths to the vendored native dependencies and the :api build outputs are injected by Gradle
# (see test-support/build.gradle.kts). They can also be set manually when invoking CMake directly:
#   cmake -S test-support/src/main/cpp -B build-native/test-support -G Ninja \
#     -DJSI_DIR=$PWD/api/build/hermes-env-jsi \
#     -DHERMES_ENV_LIBS_DIR=$PWD/api/build/hermes-env-libs \
#     -DEXPO_API_LIBS_DIR=$PWD/api/build/native-libs
if (NOT DEFINED JSI_DIR)
  set(JSI_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../api/build/hermes-env-jsi")
endif ()
if (NOT DEFINED HERMES_ENV_LIBS_DIR)
  set(HERMES_ENV_LIBS_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../api/build/hermes-env-libs")
endif ()
if (NOT DEFINED KOLIBRI_DIR)
  set(KOLIBRI_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../api/build/kolibri-cpp")
endif ()
if (NOT DEFINED EXPO_API_CPP_DIR)
  set(EXPO_API_CPP_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../api/src/main/cpp")
endif ()
if (NOT DEFINED EXPO_API_LIBS_DIR)
  set(EXPO_API_LIBS_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../api/build/native-libs")
endif ()

# Gradle injects -DJAVA_HOME (the JNI headers come from it). IDE CMake syncs configure this file
# directly without any -D vars, so derive it from the environment or the macOS java_home helper.
if (NOT DEFINED JAVA_HOME)
  if (DEFINED ENV{JAVA_HOME})
    set(JAVA_HOME "$ENV{JAVA_HOME}")
  elseif (APPLE)
    execute_process(
      COMMAND /usr/libexec/java_home
      OUTPUT_VARIABLE JAVA_HOME
      OUTPUT_STRIP_TRAILING_WHITESPACE
      RESULT_VARIABLE JAVA_HOME_RESULT
    )
    if (NOT JAVA_HOME_RESULT EQUAL 0)
      message(FATAL_ERROR "JAVA_HOME is not set and /usr/libexec/java_home failed; pass -DJAVA_HOME=<jdk>.")
    endif ()
  endif ()
endif ()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- The Hermes host library (imported) ----------------------------------------------------
# hermes-tests-environment: the VM plus the one process-wide copy of jsi. Every jsi symbol this
# library uses resolves here, the same way it does for the :api bridge below.
add_library(hermes-env SHARED IMPORTED)
set_target_properties(hermes-env PROPERTIES
        IMPORTED_LOCATION ${HERMES_ENV_LIBS_DIR}/libhermes-test-env.dylib)
target_include_directories(hermes-env INTERFACE ${JSI_DIR})

# --- The :api bridge dylib (imported) ------------------------------------------------------
# Provides — as exported symbols — the kolibri layer (whose global state must exist exactly once
# in the process), the converters/binary codec and the JavaScriptRuntime base. Its headers come
# from the source trees.
add_library(expo-api-bridge SHARED IMPORTED)
set_target_properties(expo-api-bridge PROPERTIES
        IMPORTED_LOCATION ${EXPO_API_LIBS_DIR}/libexpo-kolibri.dylib)
target_include_directories(expo-api-bridge INTERFACE
        ${EXPO_API_CPP_DIR}
        ${EXPO_API_CPP_DIR}/expo-jsi
        ${KOLIBRI_DIR}
        ${JAVA_HOME}/include
        ${JAVA_HOME}/include/darwin
)

# --- The test-support library ----------------------------------------------------------------
# The test/benchmark host-function suite (globalThis.ExpoTestSupport), kept out of the :api bridge
# so the shipped library carries no test code. Installed into a runtime on demand via the Kotlin
# `TestSupport.install` entry point (OnLoad.cpp).
add_library(expo-test-support SHARED
        BinaryTestPipeline.cpp
        FollyBenchmark.cpp
        KolibriArrayCheck.cpp
        OnLoad.cpp
        TestSupportObject.cpp
)

target_compile_options(expo-test-support PRIVATE -Wall -Wextra -Wpedantic -Werror)

target_link_libraries(expo-test-support PRIVATE expo-api-bridge hermes-env)

# Resolve @rpath/libexpo-kolibri.dylib and @rpath/libhermes-test-env.dylib at runtime against the
# directories they are built into and unpacked into.
set_target_properties(expo-test-support PROPERTIES
        BUILD_RPATH "${EXPO_API_LIBS_DIR};${HERMES_ENV_LIBS_DIR}")

# --- folly::dynamic comparison benchmark (opt-in) ----------------------------------------
# Compares React Native's folly::dynamic representation against the binary buffer codec.
# OFF by default: folly is a machine-local dependency (e.g. `brew install folly`), never a
# build requirement. Enable with -DEXPO_FOLLY_BENCHMARK=ON; the __follyBench host function is
# only installed in that configuration (see FollyBenchmark.cpp).
option(EXPO_FOLLY_BENCHMARK "Build the folly::dynamic comparison benchmark (requires folly)" OFF)
if (EXPO_FOLLY_BENCHMARK)
    # folly's exported targets link against gflags' non-namespaced `gflags_shared` target; load
    # gflags' own config first so that target exists (otherwise the name leaks as -lgflags_shared).
    find_package(gflags CONFIG REQUIRED)
    find_package(folly CONFIG REQUIRED)
    # JSIDynamic (jsi::Value <-> folly::dynamic) ships inside the JSI tree but belongs to no
    # Hermes target, so the host publishes its source next to the headers; compile it in only for
    # this configuration.
    target_sources(expo-test-support PRIVATE ${JSI_DIR}/jsi/JSIDynamic.cpp)
    target_compile_definitions(expo-test-support PRIVATE EXPO_FOLLY_BENCHMARK)
    # Boost's imported targets (pulled in through Folly::folly) define BOOST_*_NO_LIB twice with
    # different spellings, which -Werror turns into a hard error in every TU.
    target_compile_options(expo-test-support PRIVATE -Wno-macro-redefined)
    if (TARGET Folly::folly)
        # Homebrew's folly bakes the SDK it was built against into its interface include dirs
        # (e.g. the CommandLineTools MacOSX15 SDK). Injecting a second SDK's headers exposes the
        # macro-style isnan/isfinite from that math.h and breaks every C++ TU — the compiler
        # already provides the right SDK, so drop those entries.
        foreach (folly_target Folly::folly Folly::folly_deps)
            if (TARGET ${folly_target})
                get_target_property(EXPO_FOLLY_INCLUDES ${folly_target} INTERFACE_INCLUDE_DIRECTORIES)
                if (EXPO_FOLLY_INCLUDES)
                    list(FILTER EXPO_FOLLY_INCLUDES EXCLUDE REGEX "\\.sdk/usr/include$")
                    set_target_properties(${folly_target} PROPERTIES
                            INTERFACE_INCLUDE_DIRECTORIES "${EXPO_FOLLY_INCLUDES}")
                endif ()
            endif ()
        endforeach ()
        target_link_libraries(expo-test-support PRIVATE Folly::folly)
    else ()
        target_include_directories(expo-test-support PRIVATE ${FOLLY_INCLUDE_DIRS})
        target_link_libraries(expo-test-support PRIVATE ${FOLLY_LIBRARIES})
    endif ()
    # folly and JSIDynamic headers don't survive our -Wpedantic -Werror set.
    set_source_files_properties(FollyBenchmark.cpp ${JSI_DIR}/jsi/JSIDynamic.cpp
            PROPERTIES COMPILE_OPTIONS "-Wno-error;-Wno-pedantic")
endif ()
