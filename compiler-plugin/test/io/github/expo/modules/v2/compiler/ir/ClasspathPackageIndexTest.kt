package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.ClasspathPackage
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ClasspathPackageIndexTest {
  private val root: File = Files.createTempDirectory("classpath-packages").toFile()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun `finds the package of an entry, also through a symbolic link`() {
    val jar = file("node_modules/.pnpm/expo-font/android/classes.jar")
    val link = root.resolve("node_modules/expo-font").toPath()
    Files.createSymbolicLink(link, root.resolve("node_modules/.pnpm/expo-font").toPath())
    val linked = link.toFile().resolve("android/classes.jar")
    val index = ClasspathPackageIndex(listOf(ClasspathPackage("expo-font", linked)))

    assertEquals("expo-font", index.packageOf(jar))
    assertNull(index.packageOf(file("node_modules/other/classes.jar")))
  }

  @Test
  fun `accepts a classpath that holds at least one entry`() {
    val debug = file("font/debug/classes.jar")
    val release = file("font/release/classes.jar")
    val index = ClasspathPackageIndex(
      listOf(ClasspathPackage("expo-font", debug), ClasspathPackage("expo-font", release)),
    )

    index.checkAnyEntryIsOn(listOf(file("other.jar"), release))
    ClasspathPackageIndex(emptyList()).checkAnyEntryIsOn(listOf(release))
  }

  @Test
  fun `fails when no entry is on the classpath`() {
    val index = ClasspathPackageIndex(listOf(ClasspathPackage("expo-font", file("font/debug/classes.jar"))))

    val error = assertFailsWith<IllegalStateException> {
      index.checkAnyEntryIsOn(listOf(file("font/release/classes.jar")))
    }
    assertContains(error.message.orEmpty(), "none of the 1 `classpathPackage` entries")
  }

  private fun file(relativePath: String): File = root.resolve(relativePath).apply {
    parentFile.mkdirs()
    writeBytes(ByteArray(0))
  }
}
