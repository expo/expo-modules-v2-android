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

  /** An absolute path on every platform: `/app` is not one on Windows. */
  private val base: File = File(System.getProperty("java.io.tmpdir")).absoluteFile

  @Test
  fun `parses a package and an absolute entry`() {
    val jar = base.resolve("font/classes.jar")
    val parsed = processor.parseClasspathPackage("expo-font=${jar.path}")

    assertEquals("expo-font" to jar, parsed.packageName to parsed.entry)
  }

  @Test
  fun `unescapes percent, comma and equals in both parts`() {
    val parsed = processor.parseClasspathPackage("a%3Db%2Cc%25=${base.path}/a%2Cb/50%25/x%3Dy.jar")

    assertEquals("a=b,c%" to File("${base.path}/a,b/50%/x=y.jar"), parsed.packageName to parsed.entry)
  }

  /**
   * The same string `ExpoModulesV2GradlePluginTest` expects the Gradle plugin to write for
   * `a=b,c%`, so the escaping there and the unescaping here cannot drift apart unnoticed.
   */
  @Test
  fun `reads what the Gradle plugin writes`() {
    val parsed = processor.parseClasspathPackage("a%3Db%2Cc%25=${base.path}")

    assertEquals("a=b,c%", parsed.packageName)
  }

  @Test
  fun `rejects a value without a package or an entry`() {
    val jar = base.resolve("font/classes.jar").path
    for (value in listOf("expo-font", "=$jar", "expo-font=", " =$jar")) {
      assertFailsWith<CliOptionProcessingException>(value) { processor.parseClasspathPackage(value) }
    }
  }

  @Test
  fun `rejects a relative entry`() {
    assertFailsWith<CliOptionProcessingException> { processor.parseClasspathPackage("expo-font=build/classes.jar") }
  }
}
