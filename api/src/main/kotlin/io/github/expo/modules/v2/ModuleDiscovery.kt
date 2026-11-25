package io.github.expo.modules.v2

fun discoveredExpoModules(): List<Class<out Module>> = throw IllegalStateException(
  "discoveredExpoModules() was not expanded at compile time, so this build has no list of Expo " +
    "modules. The Expo Modules v2 Kotlin compiler plugin rewrites this call into the modules it " +
    "finds on the compile classpath; apply the `io.github.expo.modules.v2` Gradle plugin to the " +
    "project that calls it, or register the modules by hand with ModuleRegistry.register().",
)

/**
 * The modules of [discoveredExpoModules], grouped by package. A module's package is the one that
 * `expoModulesV2.classpathPackages` of the calling project gives to the classpath entry the module
 * comes from, for example the npm package `expo-font` of a library's classes jar. Packages are
 * sorted by name, and the modules of a package by class name. The calling project's own modules,
 * and the modules of an entry without a package, are not in any package.
 */
fun discoveredExpoPackages(): Map<String, ExpoPackage> = throw IllegalStateException(
  "discoveredExpoPackages() was not expanded at compile time, so this build has no list of Expo " +
    "packages. The Expo Modules v2 Kotlin compiler plugin rewrites this call into the modules it " +
    "finds on the compile classpath, grouped by `expoModulesV2.classpathPackages`; apply the " +
    "`io.github.expo.modules.v2` Gradle plugin to the project that calls it.",
)
