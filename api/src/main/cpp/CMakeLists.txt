cmake_minimum_required(VERSION 3.18)
project(expo-modules-v2 CXX)

# Paths to the unpacked native dependencies are injected by Gradle (see api/build.gradle.kts).
# They can also be set manually when invoking CMake directly:
#   cmake -S api/src/main/cpp -B build-native/api -G Ninja \
#     -DJSI_DIR=$PWD/api/build/hermes-env-jsi \
#     -DHERMES_ENV_LIBS_DIR=$PWD/api/build/hermes-env-libs
if (NOT DEFINED JSI_DIR)
  set(JSI_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../build/hermes-env-jsi")
endif ()
if (NOT DEFINED HERMES_ENV_LIBS_DIR)
  set(HERMES_ENV_LIBS_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../build/hermes-env-libs")
endif ()

# Gradle injects -DJAVA_HOME (kolibri uses it for the JNI include paths). IDE CMake syncs
# configure this file directly without any -D vars, so derive it from the environment or the
# macOS java_home helper.
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

# --- JSI (imported from the host that owns the runtime) ---------------------------------
# This build compiles no JSI of its own: exactly one copy may exist in a process, because two
# would disagree about object layout and carry distinct vtables and typeinfos, breaking
# dynamic_cast and catch across library boundaries. The copy comes from whoever creates the
# runtime — hermes-tests-environment here, React Native on Android (see
# react/src/main/cpp/CMakeLists.txt, which aliases this same target name onto
# ReactAndroid::jsi). The headers are unpacked from that host's published `jsi-cpp` zip, so
# nothing here vendors Hermes.
add_library(jsi SHARED IMPORTED)
set_target_properties(jsi PROPERTIES
        IMPORTED_LOCATION ${HERMES_ENV_LIBS_DIR}/libhermes-test-env${CMAKE_SHARED_LIBRARY_SUFFIX})
target_include_directories(jsi INTERFACE ${JSI_DIR})

# --- expo::kolibri (standalone JNI layer) -----------------------------------------------
# The kolibri static library, built from the sources Gradle unpacks out of the published
# `cpp` zip (:api:unpackKolibriCpp); it only needs <jni.h> and exposes the JNI + its own include
# root as PUBLIC usage requirements, so linking against it is enough. Its headers are included as
# #include <kolibri/...> (metaprogramming under <kolibri/meta/...>). The fallback below is for IDE
# CMake syncs that do not inject -DKOLIBRI_DIR; run :api:unpackKolibriCpp once to populate it.
if (NOT DEFINED KOLIBRI_DIR)
  set(KOLIBRI_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../build/kolibri-cpp")
endif ()
add_subdirectory(${KOLIBRI_DIR} kolibri-build)

# --- expo::jsi (engine-agnostic JSI-only helpers) ----------------------------------------
# Static library under expo-jsi/; depends only on the jsi target above.
add_subdirectory(expo-jsi)

# --- The Expo Modules API v2 JNI bridge ------------------------------------------------
# Every .cpp under expo-modules-v2/ is part of the bridge; its headers are included as
# #include <expo-modules-v2/...>, mirroring the kolibri/expo-jsi include-root convention.
# CONFIGURE_DEPENDS makes the build re-glob when the set of files changes, so new sources are
# picked up without editing this list.
file(GLOB_RECURSE EXPO_MODULES_V2_SOURCES CONFIGURE_DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/expo-modules-v2/*.cpp"
)
add_library(expo-kolibri SHARED ${EXPO_MODULES_V2_SOURCES})

target_include_directories(expo-kolibri PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Compile our own sources with the full warning set, and treat every warning as a hard error so
# regressions can't slip in.
target_compile_options(expo-kolibri PRIVATE -Wall -Wextra -Wpedantic -Werror)

# jsi is not listed directly: expo-jsi already links it PUBLIC.
target_link_libraries(expo-kolibri PRIVATE
        kolibri
        expo-jsi
)

# Resolve @rpath/libhermes-test-env.dylib at runtime against the directory the engine artifact is
# unpacked into.
set_target_properties(expo-kolibri PROPERTIES BUILD_RPATH ${HERMES_ENV_LIBS_DIR})
