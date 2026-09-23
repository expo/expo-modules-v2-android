package io.github.expo.modules.v2.compiler

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar

/**
 * The base of [ExpoModulesV2ComponentRegistrar]. Kotlin 2.3.0+ variant: [CompilerPluginRegistrar]
 * requires every registrar to declare its `pluginId`.
 */
abstract class ExpoCompilerPluginRegistrar : CompilerPluginRegistrar() {
  override val pluginId: String
    get() = BuildConfig.KOTLIN_PLUGIN_ID
}
