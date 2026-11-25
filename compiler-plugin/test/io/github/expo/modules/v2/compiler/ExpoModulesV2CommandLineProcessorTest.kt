package io.github.expo.modules.v2.compiler

import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Calls the parser directly: from Kotlin 2.4, creating a CompilerConfiguration needs an opt-in that
// older releases do not have.
class ExpoModulesV2CommandLineProcessorTest {
  private val processor = ExpoModulesV2CommandLineProcessor()

  @Test
  fun `parses a package and an absolute entry`() {
    val parsed = processor.parseClasspathPackage("expo-font=/app/font/classes.jar")

    assertEquals("expo-font" to File("/app/font/classes.jar"), parsed.packageName to parsed.entry)
  }

  @Test
  fun `unescapes percent, comma and equals in both parts`() {
    val parsed = processor.parseClasspathPackage("a%3Db%2Cc%25=/Users/a%2Cb/50%25/x%3Dy.jar")

    assertEquals("a=b,c%" to File("/Users/a,b/50%/x=y.jar"), parsed.packageName to parsed.entry)
  }

  @Test
  fun `rejects a value without a package or an entry`() {
    for (value in listOf("expo-font", "=/app/font/classes.jar", "expo-font=", " =/app/font/classes.jar")) {
      assertFailsWith<CliOptionProcessingException>(value) { processor.parseClasspathPackage(value) }
    }
  }

  @Test
  fun `rejects a relative entry`() {
    assertFailsWith<CliOptionProcessingException> { processor.parseClasspathPackage("expo-font=build/classes.jar") }
  }
}
