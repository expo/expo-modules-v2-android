package io.github.expo.modules.v2.compiler.runners

import io.github.expo.modules.v2.compiler.handlers.RecordKotlinLikeDumpHandler
import io.github.expo.modules.v2.compiler.handlers.StaleDumpHandler
import io.github.expo.modules.v2.compiler.services.ExpoTestDirectives
import io.github.expo.modules.v2.compiler.services.configurePlugin
import io.github.expo.modules.v2.compiler.services.configureRecordsRuntime
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.backend.handlers.IrPrettyKotlinDumpHandler
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.builders.configureIrHandlersStep
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.runners.codegen.AbstractJvmBlackBoxCodegenTestBase
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

// Kotlin 2.4.20 dropped AbstractFirBlackBoxCodegenTestBase; the JVM base takes the parser directly.
open class AbstractJvmBoxTest : AbstractJvmBlackBoxCodegenTestBase(FirParser.LightTree) {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
    return EnvironmentBasedStandardLibrariesPathProvider
  }

  override fun configure(builder: TestConfigurationBuilder) = with(builder) {
    super.configure(this)
    // No dump is on by default: `box()` checks the behaviour, and a test opts into the dumps it
    // needs (see ExpoTestDirectives).
    useDirectives(ExpoTestDirectives)
    defaultDirectives {
      +JvmEnvironmentConfigurationDirectives.FULL_JDK
      // :api is compiled with the build's JDK 17 toolchain; its inline functions (codecFor)
      // cannot be inlined into a lower target.
      JvmEnvironmentConfigurationDirectives.JVM_TARGET with JvmTarget.JVM_17

      +CodegenTestDirectives.IGNORE_DEXING // Avoids loading R8 from the classpath.
    }

    // The codegen pipeline registers only IrTextDumpHandler, so DUMP_KT_IR is a no-op until a
    // pretty-Kotlin handler is added by hand. The stock handler dumps the whole file under
    // DUMP_KT_IR; ours narrows the same IR to the declarations the plugin generated, under
    // DUMP_GENERATED. StaleDumpHandler catches a `*.fir.txt` left behind without FIR_DUMP.
    configureIrHandlersStep {
      useHandlers(::IrPrettyKotlinDumpHandler, ::RecordKotlinLikeDumpHandler, ::StaleDumpHandler)
    }

    configurePlugin()
    configureRecordsRuntime()
  }
}
