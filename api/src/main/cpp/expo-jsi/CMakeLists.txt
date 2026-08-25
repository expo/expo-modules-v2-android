# expo::jsi — engine-agnostic JSI-only helpers. Depends solely on the jsi target (defined by
# the parent before this subdirectory is added). Headers are included as #include <expo-jsi/...>,
# mirroring the kolibri include-root convention.
file(GLOB_RECURSE EXPO_JSI_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/expo-jsi/*.cpp")
add_library(expo-jsi STATIC ${EXPO_JSI_SOURCES})

# Linked into consumers' shared libraries, so it has to be relocatable.
set_target_properties(expo-jsi PROPERTIES POSITION_INDEPENDENT_CODE ON)

# PUBLIC so consumers inherit both the <expo-jsi/...> include root and (via jsi) the
# <jsi/jsi.h> include dirs our own headers need.
target_include_directories(expo-jsi PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(expo-jsi PUBLIC jsi)

target_compile_options(expo-jsi PRIVATE -Wall -Wextra -Wpedantic -Werror)
