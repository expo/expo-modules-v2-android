package io.github.expo.modules.v2.compiler.ir

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleHintScannerTest {
  private val root: File = Files.createTempDirectory("hint-scanner").toFile()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun `finds hints in jars and class directories`() {
    val jar = jar("lib/module-a.jar", "$HINTS/a_Module_00000001.class")
    val dir = root.resolve("classes").apply {
      resolve(HINTS).mkdirs()
      resolve("$HINTS/b_Module_00000002.class").writeBytes(ByteArray(0))
    }

    assertEquals(
      listOf("a_Module_00000001", "b_Module_00000002"),
      ModuleHintScanner { listOf(jar, dir) }.hints.map { it.shortClassName.asString() },
    )
  }

  @Test
  fun `ignores nested classes, sub packages and files that are not classes`() {
    val jar = jar(
      "lib/noise.jar",
      "$HINTS/a_Module_00000001.class",
      "$HINTS/a_Module_00000001\$Inner.class",
      "$HINTS/sub/c_Module_00000003.class",
      "$HINTS/notes.txt",
      "io/github/expo/modules/v2/other/d_Module_00000004.class",
    )

    assertEquals(
      listOf("a_Module_00000001"),
      ModuleHintScanner { listOf(jar) }.hints.map { it.shortClassName.asString() },
    )
  }

  @Test
  fun `does not open the Android platform jar`() {
    // A hint inside android.jar is impossible in practice; here it proves the jar was skipped
    // rather than scanned and found empty.
    val platform = jar("sdk/platforms/android-36/android.jar", "$HINTS/x_Planted_00000009.class")
    val library = jar("lib/module-a.jar", "$HINTS/a_Module_00000001.class")

    assertEquals(
      listOf("a_Module_00000001"),
      ModuleHintScanner { listOf(platform, library) }.hints.map { it.shortClassName.asString() },
    )
  }

  @Test
  fun `does not open toolchain jars that cannot hold a module`() {
    val planted = listOf(
      jar("sdk/build-tools/36.0.0/core-lambda-stubs.jar", "$HINTS/p1_Planted_00000001.class"),
      jar("cache/kotlin-stdlib-2.2.0.jar", "$HINTS/p2_Planted_00000002.class"),
      jar("cache/kotlin-stdlib-jdk8-2.2.0.jar", "$HINTS/p3_Planted_00000003.class"),
      jar("cache/kotlin-reflect-2.2.0.jar", "$HINTS/p4_Planted_00000004.class"),
      jar("cache/kotlinx-coroutines-core-jvm-1.10.2.jar", "$HINTS/p5_Planted_00000005.class"),
      jar("transformed/react-android-0.88.0-debug-api.jar", "$HINTS/p6_Planted_00000006.class"),
      jar("transformed/hermes-android-0.88.0-debug-api.jar", "$HINTS/p7_Planted_00000007.class"),
    )
    // Names that only share a prefix with the toolchain are not skipped.
    val lookalike = jar("lib/kotlin-module-1.0.jar", "$HINTS/a_Module_00000001.class")
    val reactModule = jar("lib/react-native-thing-1.0-api.jar", "$HINTS/b_Module_00000002.class")

    assertEquals(
      listOf("a_Module_00000001", "b_Module_00000002"),
      ModuleHintScanner { planted + lookalike + reactModule }.hints.map { it.shortClassName.asString() },
    )
  }

  @Test
  fun `finds hints whether or not a jar records directory entries`() {
    // Written by tools such as AGP and Gradle: every directory has its own entry, so the hints
    // directory can be looked up without walking the names.
    val withDirectories = jar(
      "lib/with-dirs.jar",
      "META-INF/", "META-INF/MANIFEST.MF", "io/", "io/github/", "io/github/expo/", "io/github/expo/modules/",
      "io/github/expo/modules/v2/", "$HINTS/", "$HINTS/a_Module_00000001.class",
    )
    // Written by tools that omit directory entries: only the walk can find the hint.
    val withoutDirectories = jar("lib/no-dirs.jar", "META-INF/MANIFEST.MF", "$HINTS/b_Module_00000002.class")
    // Records directories but has no hints package: the lookup alone settles it.
    val unrelated = jar("lib/unrelated.jar", "META-INF/", "com/", "com/example/", "com/example/Foo.class")

    assertEquals(
      listOf("a_Module_00000001", "b_Module_00000002"),
      ModuleHintScanner { listOf(withDirectories, withoutDirectories, unrelated) }.hints
        .map { it.shortClassName.asString() },
    )
  }

  @Test
  fun `skips roots that are not archives`() {
    val notAZip = root.resolve("lib/broken.jar").apply {
      parentFile.mkdirs()
      writeText("this is not a zip file")
    }
    val library = jar("lib/module-a.jar", "$HINTS/a_Module_00000001.class")

    assertEquals(
      listOf("a_Module_00000001"),
      ModuleHintScanner { listOf(notAZip, library) }.hints.map { it.shortClassName.asString() },
    )
  }

  private fun jar(relativePath: String, vararg entries: String): File {
    val file = root.resolve(relativePath)
    file.parentFile.mkdirs()
    ZipOutputStream(file.outputStream()).use { zip ->
      entries.forEach { entry ->
        zip.putNextEntry(ZipEntry(entry))
        zip.closeEntry()
      }
    }
    return file
  }

  private companion object {
    const val HINTS = "io/github/expo/modules/v2/hints"
  }
}
