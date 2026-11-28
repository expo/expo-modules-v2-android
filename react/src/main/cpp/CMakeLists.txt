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

# PROTOTYPE (views): the Fabric component descriptor compiles against React Native's renderer and
# fbjni, and has to agree with how React Native built them. `RN_SERIALIZABLE_STATE` changes the
# layout of `Props` and `State`, and folly's flags change its headers, so these mirror
# ReactAndroid/cmake-utils (folly-flags.cmake, ReactNative-application.cmake).
find_package(fbjni REQUIRED CONFIG)
set(EXPO_MODULES_V2_REACT_NATIVE_FLAGS
        -DRN_SERIALIZABLE_STATE=1
        -DFOLLY_NO_CONFIG=1
        -DFOLLY_HAVE_CLOCK_GETTIME=1
        -DFOLLY_USE_LIBCPP=1
        -DFOLLY_CFG_NO_COROUTINES=1
        -DFOLLY_MOBILE=1
        -DFOLLY_HAVE_RECVMMSG=1
        -DFOLLY_HAVE_PTHREAD=1
        -DFOLLY_HAVE_XSI_STRERROR_R=1
)

add_subdirectory(${EXPO_API_CPP_DIR}/expo-jsi expo-jsi-build)

file(GLOB_RECURSE EXPO_MODULES_V2_SOURCES CONFIGURE_DEPENDS
        "${EXPO_API_CPP_DIR}/expo-modules-v2/*.cpp"
)
list(REMOVE_ITEM EXPO_MODULES_V2_SOURCES "${EXPO_API_CPP_DIR}/expo-modules-v2/OnLoad.cpp")

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

target_compile_options(expo-modules-v2 PRIVATE ${EXPO_MODULES_V2_WARNINGS} ${EXPO_MODULES_V2_REACT_NATIVE_FLAGS})

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
# jsi is not listed: expo-jsi links it PUBLIC.
target_link_libraries(expo-modules-v2 PRIVATE
        kolibri-android::kolibri
        expo-jsi
        # PROTOTYPE (views): the renderer (libreactnative.so) and fbjni, both already in every app.
        ReactAndroid::reactnative
        fbjni::fbjni
        log
)
