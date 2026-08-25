cmake_minimum_required(VERSION 3.22)
project(expo-modules-v2-android CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Gradle injects the path to :api's C++ tree (see react/build.gradle.kts). The fallback keeps
# an IDE CMake sync, which passes no -D vars, working.
if (NOT DEFINED EXPO_API_CPP_DIR)
  set(EXPO_API_CPP_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../api/src/main/cpp")
endif ()

# --- JSI (React Native's copy) ------------------------------------------------------------------
# The desktop build compiles the JSI sources vendored in the Hermes tree into its own dylib. Here it
# must be React Native's `libjsi.so`: the runtime this library attaches to was created by RN, and
# two copies of the JSI ABI in one process would disagree about object layout. The alias is what
# lets :api's own expo-jsi/CMakeLists.txt keep linking a target called `jsi`.
find_package(ReactAndroid REQUIRED CONFIG)
add_library(jsi ALIAS ReactAndroid::jsi)

# --- expo::kolibri (standalone JNI layer) --------------------------------------------------------
# Prebuilt, unlike the desktop build which compiles it from the published `cpp` zip: the Kolibri
# Gradle plugin adds the kolibri-android Prefab AAR (headers + libkolibri.a per ABI) to any Android
# project it is applied to.
find_package(kolibri-android REQUIRED CONFIG)

# --- expo::jsi (engine-agnostic JSI-only helpers) ------------------------------------------------
add_subdirectory(${EXPO_API_CPP_DIR}/expo-jsi expo-jsi-build)

# --- The Expo Modules API v2 JNI bridge ---------------------------------------------------------
# Every .cpp under :api's expo-modules-v2/ except its OnLoad.cpp: this library needs a single
# JNI_OnLoad that also registers the Android runtime's natives, and two definitions in one .so
# would collide.
file(GLOB_RECURSE EXPO_MODULES_V2_SOURCES CONFIGURE_DEPENDS
        "${EXPO_API_CPP_DIR}/expo-modules-v2/*.cpp"
)
list(REMOVE_ITEM EXPO_MODULES_V2_SOURCES "${EXPO_API_CPP_DIR}/expo-modules-v2/OnLoad.cpp")

file(GLOB_RECURSE EXPO_MODULES_V2_REACT_SOURCES CONFIGURE_DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/expo-modules-v2-react/*.cpp"
)

# Named `expo-kolibri` because that is the library kolibri's own `Kolibri.load()` asks
# System.loadLibrary for; on Android everything ships in this one .so.
add_library(expo-kolibri SHARED
        ${EXPO_MODULES_V2_SOURCES}
        ${EXPO_MODULES_V2_REACT_SOURCES}
)

target_include_directories(expo-kolibri PRIVATE
        ${EXPO_API_CPP_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_options(expo-kolibri PRIVATE -Wall -Wextra -Wpedantic -Werror)

# `log` is for __android_log_print, the only place ExpoModulesCore.nativeLog can write on Android.
# jsi is not listed: expo-jsi links it PUBLIC.
target_link_libraries(expo-kolibri PRIVATE
        kolibri-android::kolibri
        expo-jsi
        log
)
