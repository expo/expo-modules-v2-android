package io.github.expo.modules.v2.compiler.runners

import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder

/** Kotlin 2.2.10: diagnostics tests append no `GENERATED_FIR_TAGS` footer, so nothing to turn off. */
internal fun TestConfigurationBuilder.disableGeneratedFirTags() = Unit
