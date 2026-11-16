package io.github.expo.modules.v2.compiler.runners

import io.github.expo.modules.v2.compiler.handlers.TypegenReportHandler
import io.github.expo.modules.v2.compiler.services.configureTypegen
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.builders.configureIrHandlersStep
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives

/**
 * Compiles each test with typegen on and compares the report the plugin writes against
 * `<test>.exports.json`. The code still compiles and `box()` still runs, so what a test describes is
 * code the plugin accepts; the box suite's IR and FIR dumps are left out, since they pin the
 * generated code, not the surface.
 */
open class AbstractJvmTypegenTest : AbstractJvmBoxTest() {
  override fun configure(builder: TestConfigurationBuilder) = with(builder) {
    super.configure(this)
    defaultDirectives {
      -CodegenTestDirectives.DUMP_IR
      -CodegenTestDirectives.DUMP_KT_IR
      -FirDiagnosticsDirectives.FIR_DUMP
    }

    configureTypegen()
    configureIrHandlersStep {
      useHandlers(::TypegenReportHandler)
    }
  }
}
