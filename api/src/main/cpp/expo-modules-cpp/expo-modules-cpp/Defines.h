#pragma once

#include <jsi/jsi.h>

// Compiler attributes, and the one namespace alias the whole library is written against.
//
// The public API lives in `expo::modules::v2::cpp`. `expo-modules-core` ships its own C++ in the
// top-level `expo` namespace (`expo::JSIContext`, `expo::SharedObject`, ...), and two libraries in
// one process that define the same name with default visibility are resolved against each other by
// the dynamic linker, so this library stays inside the namespace this repository already owns.

/** Marks what a module library must resolve against the one copy in the runtime library. */
#define EXPO_API [[gnu::visibility("default")]]

#define EXPO_COLD [[gnu::cold]]

#define EXPO_NOINLINE [[gnu::noinline]]

#define EXPO_ALWAYS_INLINE [[gnu::always_inline]] inline

namespace expo::modules::v2::cpp {
  /**
   * JSI. Spelled out as `js::` because `expo::jsi` (the helper library) and
   * `expo::modules::v2::jsi` (the JNI bridge) are namespaces of their own, and an unqualified `jsi`
   * resolves to whichever of them is nearer.
   */
  namespace js = ::facebook::jsi;
} // namespace expo::modules::v2::cpp

namespace expo {
  namespace cpp = ::expo::modules::v2::cpp;
}
