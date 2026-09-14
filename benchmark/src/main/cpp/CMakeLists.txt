cmake_minimum_required(VERSION 3.18)
project(expo-benchmark CXX)

# Paths to the vendored native dependencies and the sibling modules' build outputs are injected
# by Gradle (see benchmark/build.gradle.kts).
if (NOT DEFINED FBJNI_CPP_DIR)
  set(FBJNI_CPP_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../api/build/hermes-env-fbjni")
endif ()
if (NOT DEFINED HERMES_ENV_LIBS_DIR)
  set(HERMES_ENV_LIBS_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../api/build/hermes-env-libs")
endif ()
if (NOT DEFINED KOLIBRI_DIR)
  set(KOLIBRI_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../api/build/kolibri-cpp")
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

# jni_md.h lives in a per-platform subdirectory of the JDK's include/ dir.
if (APPLE)
  set(JNI_MD_SUBDIR darwin)
else ()
  set(JNI_MD_SUBDIR linux)
endif ()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- The :api bridge dylib (imported) ------------------------------------------------------
# Provides the kolibri layer (JavaClass tokens, env, native_method) as exported symbols — its
# global state must exist exactly once in the process, so it is never re-linked statically here.
add_library(expo-api-bridge SHARED IMPORTED)
set_target_properties(expo-api-bridge PROPERTIES
        IMPORTED_LOCATION ${EXPO_API_LIBS_DIR}/libexpo-kolibri${CMAKE_SHARED_LIBRARY_SUFFIX})
target_include_directories(expo-api-bridge INTERFACE
        ${KOLIBRI_DIR}
        ${JAVA_HOME}/include
        ${JAVA_HOME}/include/${JNI_MD_SUBDIR}
)

# --- fbjni (imported) -------------------------------------------------------------------------
# One of the benchmarked dispatch mechanisms. Built and shipped by hermes-test-environment, so this
# repository vendors nothing: the library comes out of its `native-libs` zip and the headers out of
# its `fbjni-cpp` zip.
add_library(fbjni SHARED IMPORTED)
set_target_properties(fbjni PROPERTIES
        IMPORTED_LOCATION ${HERMES_ENV_LIBS_DIR}/libfbjni${CMAKE_SHARED_LIBRARY_SUFFIX})
target_include_directories(fbjni INTERFACE ${FBJNI_CPP_DIR})

# --- The benchmark library -------------------------------------------------------------------
add_library(expo-benchmark SHARED
        JniCallBenchmark.cpp
        OnLoad.cpp
)

target_compile_options(expo-benchmark PRIVATE -Wall -Wextra -Wpedantic -Werror)

# The benchmark file includes fbjni's headers, which don't survive our -Wpedantic -Werror; relax the
# warning set for just this translation unit (later flags win over the target-level ones above).
set_source_files_properties(JniCallBenchmark.cpp PROPERTIES
        COMPILE_OPTIONS "-Wno-error;-Wno-pedantic")

target_link_libraries(expo-benchmark PRIVATE
        expo-api-bridge
        fbjni
)

# Resolve @rpath/libexpo-kolibri.dylib and @rpath/libfbjni.dylib at runtime against :api's shipped
# native-libs dir and the unpacked host libraries.
set_target_properties(expo-benchmark PROPERTIES
        BUILD_RPATH "${EXPO_API_LIBS_DIR};${HERMES_ENV_LIBS_DIR}")
