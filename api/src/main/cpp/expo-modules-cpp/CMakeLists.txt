# expo::modules::v2::cpp — the C++ API for Expo Modules v2. Engine-agnostic and JNI-free: it depends
# only on the jsi target (defined by the parent before this subdirectory is added) and on expo-jsi.
# Headers are included as #include <expo-modules-cpp/...>, mirroring the expo-jsi convention.
#
# A static library: it is linked into the library that serves the modules (libexpo-modules-v2 on
# Android), and into nothing else. Two copies in one process would each hold their own module
# registry and per-runtime state, and a module could not find the runtime it was attached to.
file(GLOB_RECURSE EXPO_MODULES_CPP_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/expo-modules-cpp/*.cpp")
add_library(expo-modules-cpp STATIC ${EXPO_MODULES_CPP_SOURCES})

# Linked into consumers' shared libraries, so it has to be relocatable.
set_target_properties(expo-modules-cpp PROPERTIES POSITION_INDEPENDENT_CODE ON)

# PUBLIC so a module's own library inherits the <expo-modules-cpp/...> include root, <expo-jsi/...>
# and <jsi/jsi.h>, and the language level the headers need (coroutines, concepts).
target_include_directories(expo-modules-cpp PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(expo-modules-cpp PUBLIC expo-jsi)
target_compile_features(expo-modules-cpp PUBLIC cxx_std_20)

if (NOT DEFINED EXPO_MODULES_V2_WARNINGS)
  set(EXPO_MODULES_V2_WARNINGS -Wall -Wextra -Wpedantic -Werror)
endif ()

target_compile_options(expo-modules-cpp PRIVATE ${EXPO_MODULES_V2_WARNINGS})
