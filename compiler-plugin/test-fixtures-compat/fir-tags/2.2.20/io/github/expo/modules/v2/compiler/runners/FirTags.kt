package io.github.expo.modules.v2.compiler.runners

import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives

/**
 * Kotlin 2.2.20+: diagnostics tests append a `GENERATED_FIR_TAGS` footer to every test file. The
 * releases before 2.2.20 do not, so it is turned off to keep one copy of the test data for all of them.
 */
internal fun TestConfigurationBuilder.disableGeneratedFirTags() {
  defaultDirectives {
    +FirDiagnosticsDirectives.DISABLE_GENERATED_FIR_TAGS
  }
}
