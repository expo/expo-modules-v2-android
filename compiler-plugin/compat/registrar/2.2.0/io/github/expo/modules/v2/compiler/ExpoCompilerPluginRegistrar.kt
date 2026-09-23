package io.github.expo.modules.v2.compiler

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar

/**
 * The base of [ExpoModulesV2ComponentRegistrar]. Kotlin 2.2.0 - 2.2.21 variant: `pluginId` is not
 * part of [CompilerPluginRegistrar] yet.
 */
abstract class ExpoCompilerPluginRegistrar : CompilerPluginRegistrar()
