package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.ClasspathPackage
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    val linked = try {
      Files.createSymbolicLink(link, root.resolve("node_modules/.pnpm/expo-font").toPath())
      link.toFile().resolve("android/classes.jar")
    } catch (_: IOException) {
      null
    } catch (_: UnsupportedOperationException) {
      null
    }
    // Windows only lets some users create symbolic links.
    assumeTrue(linked != null, "symbolic links cannot be created here")
    val index = ClasspathPackageIndex(listOf(ClasspathPackage("expo-font", linked!!)))

    assertEquals("expo-font", index.packageOf(jar))
    assertNull(index.packageOf(file("node_modules/other/classes.jar")))
  }

  @Test
  fun `an index without options gives no package and has no conflicts`() {
    val index = ClasspathPackageIndex(emptyList())

    assertTrue(index.isEmpty)
    assertNull(index.packageOf(file("font/classes.jar")))
    assertEquals(emptyList(), index.conflicts)
  }

  @Test
  fun `one package on several entries is not a conflict`() {
    val debug = file("font/debug/classes.jar")
    val release = file("font/release/classes.jar")
    val index = ClasspathPackageIndex(
      listOf(ClasspathPackage("expo-font", debug), ClasspathPackage("expo-font", release)),
    )

    assertEquals("expo-font", index.packageOf(debug))
    assertEquals("expo-font", index.packageOf(release))
    assertEquals(emptyList(), index.conflicts)
  }

  @Test
  fun `two packages for one entry are a conflict, and the first one wins`() {
    val jar = file("font/classes.jar")
    // Another spelling of the same entry.
    val sameJar = root.resolve("font/../font/classes.jar")
    val index = ClasspathPackageIndex(
      listOf(ClasspathPackage("expo-font", jar), ClasspathPackage("expo-other", sameJar)),
    )

    assertEquals("expo-font", index.packageOf(jar))
    val conflict = index.conflicts.single()
    assertContains(conflict, "'expo-font'")
    assertContains(conflict, "'expo-other'")
  }

  private fun file(relativePath: String): File = root.resolve(relativePath).apply {
    parentFile.mkdirs()
    writeBytes(ByteArray(0))
  }
}
