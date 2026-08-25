package expo.modules.v2.compiler.runners

import expo.modules.v2.compiler.handlers.RecordKotlinLikeDumpHandler
import expo.modules.v2.compiler.services.configurePlugin
import expo.modules.v2.compiler.services.configureRecordsRuntime
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.backend.handlers.IrPrettyKotlinDumpHandler
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.builders.configureIrHandlersStep
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.runners.codegen.AbstractFirBlackBoxCodegenTestBase
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

open class AbstractJvmBoxTest : AbstractFirBlackBoxCodegenTestBase(FirParser.LightTree) {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
    return EnvironmentBasedStandardLibrariesPathProvider
  }

  override fun configure(builder: TestConfigurationBuilder) = with(builder) {
    super.configure(this)
    defaultDirectives {
      +CodegenTestDirectives.DUMP_IR
      // Renders the IR as Kotlin-like source, into `*.fir.kt.txt` and `*.fir.generated.kt.txt`.
      +CodegenTestDirectives.DUMP_KT_IR
      +FirDiagnosticsDirectives.FIR_DUMP
      +JvmEnvironmentConfigurationDirectives.FULL_JDK
      // :api is compiled with the build's JDK 17 toolchain; its inline functions (codecFor)
      // cannot be inlined into a lower target.
      JvmEnvironmentConfigurationDirectives.JVM_TARGET with JvmTarget.JVM_17

      +CodegenTestDirectives.IGNORE_DEXING // Avoids loading R8 from the classpath.
    }

    // The codegen pipeline registers only IrTextDumpHandler, so DUMP_KT_IR is a no-op until a
    // pretty-Kotlin handler is added by hand. The stock handler dumps the whole file; ours narrows
    // the same IR to the declarations the plugin generated.
    configureIrHandlersStep {
      useHandlers(::IrPrettyKotlinDumpHandler, ::RecordKotlinLikeDumpHandler)
    }

    configurePlugin()
    configureRecordsRuntime()
  }
}
