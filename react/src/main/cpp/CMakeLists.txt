cmake_minimum_required(VERSION 3.22)
project(expo-modules-v2-android CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT DEFINED EXPO_API_CPP_DIR)
  if (EXISTS "${CMAKE_CURRENT_LIST_DIR}/expo-modules-v2")
    set(EXPO_API_CPP_DIR "${CMAKE_CURRENT_LIST_DIR}")
  else ()
    set(EXPO_API_CPP_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../api/src/main/cpp")
  endif ()
endif ()

option(EXPO_MODULES_V2_WERROR "Treat warnings in Expo Modules v2 sources as errors" OFF)
if (EXPO_MODULES_V2_WERROR)
  set(EXPO_MODULES_V2_WARNINGS -Wall -Wextra -Wpedantic -Werror)
else ()
  set(EXPO_MODULES_V2_WARNINGS -Wall -Wextra -Wpedantic)
endif ()

set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

add_compile_options($<$<NOT:$<CONFIG:Debug>>:-flto>)

find_package(ReactAndroid REQUIRED CONFIG)
add_library(jsi ALIAS ReactAndroid::jsi)

find_package(kolibri-android REQUIRED CONFIG)

add_subdirectory(${EXPO_API_CPP_DIR}/expo-jsi expo-jsi-build)

# The C++ API for writing modules. It adds code to this library whether or not the app has a C++
# module, so an app that has none can turn it off: -DEXPO_MODULES_V2_CPP_API=OFF.
option(EXPO_MODULES_V2_CPP_API "Serve modules written with the C++ API next to the Kotlin ones" ON)
if (EXPO_MODULES_V2_CPP_API)
  add_subdirectory(${EXPO_API_CPP_DIR}/expo-modules-cpp expo-modules-cpp-build)
endif ()

file(GLOB_RECURSE EXPO_MODULES_V2_SOURCES CONFIGURE_DEPENDS
        "${EXPO_API_CPP_DIR}/expo-modules-v2/*.cpp"
)
list(REMOVE_ITEM EXPO_MODULES_V2_SOURCES "${EXPO_API_CPP_DIR}/expo-modules-v2/OnLoad.cpp")
if (NOT EXPO_MODULES_V2_CPP_API)
  list(REMOVE_ITEM EXPO_MODULES_V2_SOURCES "${EXPO_API_CPP_DIR}/expo-modules-v2/async/JniScheduler.cpp")
endif ()

file(GLOB_RECURSE EXPO_MODULES_V2_REACT_SOURCES CONFIGURE_DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/expo-modules-v2-react/*.cpp"
)

add_library(expo-modules-v2 SHARED
        ${EXPO_MODULES_V2_SOURCES}
        ${EXPO_MODULES_V2_REACT_SOURCES}
)

target_include_directories(expo-modules-v2 PRIVATE
        ${EXPO_API_CPP_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_options(expo-modules-v2 PRIVATE ${EXPO_MODULES_V2_WARNINGS})

# Hidden visibility cannot reach template parameter objects of a default-visibility type, such as
# the std::array JNI signatures kolibri passes as template arguments, and their mangled names spell
# the string out one character at a time. Nothing outside this library refers to them. Every other
# exported symbol, typeinfo and vtables included, stays exported.
set(EXPO_MODULES_V2_VERSION_SCRIPT "${CMAKE_CURRENT_BINARY_DIR}/expo-modules-v2.map")
file(WRITE "${EXPO_MODULES_V2_VERSION_SCRIPT}" "{\n  global: *;\n  local: _ZTA*;\n};\n")

target_link_options(expo-modules-v2 PRIVATE
        # kolibri's prebuilt archive is compiled with default visibility; keep its symbols local too.
        -Wl,--exclude-libs,ALL
        "-Wl,--version-script=${EXPO_MODULES_V2_VERSION_SCRIPT}"
        # Android's packed relocation format, which the loader reads from API 23 (this AAR needs 24).
        -Wl,--pack-dyn-relocs=android
        $<$<NOT:$<CONFIG:Debug>>:-flto>
        # Folds identical functions, mostly template instances that differ only in type. Safe here:
        # neither the bridge nor kolibri compares function addresses.
        $<$<NOT:$<CONFIG:Debug>>:-Wl,--icf=all>
)

# `log` is for __android_log_print, the only place ExpoModulesCore.nativeLog can write on Android.
# jsi is not listed: expo-jsi links it PUBLIC. expo-modules-cpp, the C++ API for modules, is linked
# into this library, which is where an app compiles its own C++ modules.
target_link_libraries(expo-modules-v2 PRIVATE
        kolibri-android::kolibri
        expo-jsi
        log
)
if (EXPO_MODULES_V2_CPP_API)
  target_compile_definitions(expo-modules-v2 PRIVATE EXPO_MODULES_V2_CPP_API=1)
  target_link_libraries(expo-modules-v2 PRIVATE expo-modules-cpp)
endif ()
