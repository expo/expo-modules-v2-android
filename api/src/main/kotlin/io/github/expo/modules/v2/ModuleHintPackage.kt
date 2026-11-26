package io.github.expo.modules.v2

/**
 * The package, such as the npm package `expo-font`, of the module a generated module hint names.
 * The Expo Modules v2 compiler plugin puts it on the hints of a compilation that was given a
 * package name, and `discoveredExpoPackages()` groups the modules by it. Not for use in code.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class ModuleHintPackage(val name: String)
