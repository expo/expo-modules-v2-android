package io.github.expo.modules.v2.compiler

import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExpoModulesV2CommandLineProcessorTest {
  private val processor = ExpoModulesV2CommandLineProcessor()

  @Test
  fun `takes a package name as it is, also a scoped one`() {
    assertEquals("expo-font", processor.parsePackageName("expo-font"))
    assertEquals("@expo/ui", processor.parsePackageName("@expo/ui"))
  }

  @Test
  fun `rejects a blank name and one with a comma`() {
    for (value in listOf("", " ", "expo-font,expo-ui")) {
      assertFailsWith<CliOptionProcessingException>(value) { processor.parsePackageName(value) }
    }
  }
}
