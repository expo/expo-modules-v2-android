package io.github.expo.modules.v2

fun discoveredExpoModules(): List<Class<out Module>> = throw IllegalStateException(
  "discoveredExpoModules() was not expanded at compile time, so this build has no list of Expo " +
    "modules. The Expo Modules v2 Kotlin compiler plugin rewrites this call into the modules it " +
    "finds on the compile classpath; apply the `io.github.expo.modules.v2` Gradle plugin to the " +
    "project that calls it, or register the modules by hand with ModuleRegistry.register().",
)

fun discoveredExpoPackages(): Map<String, ExpoPackage> = throw IllegalStateException(
  "discoveredExpoPackages() was not expanded at compile time, so this build has no list of Expo " +
    "packages. The Expo Modules v2 Kotlin compiler plugin rewrites this call into the modules it " +
    "finds on the compile classpath, grouped by the package each was built with; apply the " +
    "`io.github.expo.modules.v2` Gradle plugin to the project that calls it.",
)
