package io.github.expo.modules.v2.gradle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/** Configuration entry point for the Expo Modules v2 Gradle plugin: `expoModulesV2 { ... }`. */
open class ExpoModulesV2GradleExtension @Inject constructor(objectFactory: ObjectFactory) {
  /** TypeScript type generation for the module's JavaScript surface. */
  val typegen: ExpoModulesV2TypegenOptions =
    objectFactory.newInstance(ExpoModulesV2TypegenOptions::class.java).apply {
      enabled.convention(false)
    }

  fun typegen(action: Action<ExpoModulesV2TypegenOptions>) = action.execute(typegen)
}

/**
 * ```kotlin
 * expoModulesV2 {
 *   typegen {
 *     enabled = true
 *   }
 * }
 * ```
 *
 * With typegen on, each of the project's non-test Kotlin compilations writes what it exports to
 * JavaScript to `build/generated/expo-modules-v2/typegen/<compilation>/exports.json`, in the format
 * of `ExpoModulesMacros scan-exports`. The `expoModulesTypegenScanner` task writes a scanner next to
 * it, which `expo-modules generate-types` from `expo-modules-cli` takes in place of the Swift one:
 *
 * ```sh
 * ./gradlew :my-module:expoModulesTypegenScanner
 * npx expo-modules generate-types --scanner <android build dir>/generated/expo-modules-v2/typegen/scanner
 * ```
 */
abstract class ExpoModulesV2TypegenOptions {
  /** Whether the compiler plugin writes the surface. Off by default. */
  abstract val enabled: Property<Boolean>

  /**
   * The compilation whose surface the scanner prints. By default `release` when the project has
   * one, as an Android library does, and `main` otherwise.
   */
  abstract val compilation: Property<String>
}
