package io.github.expo.modules.v2

fun discoveredExpoModules(): List<Class<out Module>> = throw IllegalStateException(
  "discoveredExpoModules() was not expanded at compile time, so this build has no list of Expo " +
    "modules. The Expo Modules v2 Kotlin compiler plugin rewrites this call into the modules it " +
    "finds on the compile classpath; apply the `io.github.expo.modules.v2` Gradle plugin to the " +
    "project that calls it, or register the modules by hand with ModuleRegistry.register().",
)

/**
 * The modules of [discoveredExpoModules], grouped by package. A module's package is the one the
 * library that declares it was built with - the compiler plugin's `packageName` option, which the
 * Gradle plugin passes from `expoModulesV2.packageName`, such as `expo-font` - and which the
 * compiler plugin records on the module's hint. The calling project needs no configuration. Packages are sorted by name, and the
 * modules of a package by class name. The modules of a library built without a package name are
 * not in any package; the calling project's own modules are in its own `packageName`, if it has one.
 *
 * Call it directly: the compiler plugin replaces the call, so a reference such as
 * `::discoveredExpoPackages` is a compile error.
 */
fun discoveredExpoPackages(): Map<String, ExpoPackage> = throw IllegalStateException(
  "discoveredExpoPackages() was not expanded at compile time, so this build has no list of Expo " +
    "packages. The Expo Modules v2 Kotlin compiler plugin rewrites this call into the modules it " +
    "finds on the compile classpath, grouped by the package each was built with; apply the " +
    "`io.github.expo.modules.v2` Gradle plugin to the project that calls it.",
)
