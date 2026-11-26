package io.github.expo.modules.v2.compiler

import org.jetbrains.kotlin.name.Name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HintPackageMemberTest {
  @Test
  fun `encodes every character other than an ASCII letter or digit`() {
    assertEquals("package_expo_2dfont", Identifiers.Hints.packageMemberFor("expo-font").asString())
    assertEquals("package__40expo_2fui", Identifiers.Hints.packageMemberFor("@expo/ui").asString())
    assertEquals("package_a_2eb_5fc_7ed", Identifiers.Hints.packageMemberFor("a.b_c~d").asString())
  }

  @Test
  fun `decodes the package it encoded`() {
    for (packageName in listOf("expo-font", "@expo/ui", "a.b_c~d", "React-Native", "pакет")) {
      assertEquals(packageName, Identifiers.Hints.packageNameOf(Identifiers.Hints.packageMemberFor(packageName)))
    }
  }

  @Test
  fun `ignores other members and malformed names`() {
    assertNull(Identifiers.Hints.packageNameOf(Identifiers.Hints.MEMBER))
    assertNull(Identifiers.Hints.packageNameOf(Name.identifier("packageName")))
    assertNull(Identifiers.Hints.packageNameOf(Name.identifier("package_expo_2")))
    assertNull(Identifiers.Hints.packageNameOf(Name.identifier("package_expo_zzfont")))
  }
}
