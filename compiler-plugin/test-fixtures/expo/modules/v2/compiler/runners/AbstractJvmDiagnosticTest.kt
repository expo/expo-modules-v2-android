package expo.modules.v2.compiler.runners

import expo.modules.v2.compiler.services.configurePlugin
import expo.modules.v2.compiler.services.configureRecordsRuntime
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.runners.AbstractFirPhasedDiagnosticTest
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

open class AbstractJvmDiagnosticTest : AbstractFirPhasedDiagnosticTest(FirParser.LightTree) {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
    return EnvironmentBasedStandardLibrariesPathProvider
  }

  override fun configure(builder: TestConfigurationBuilder) = with(builder) {
    super.configure(builder)
    defaultDirectives {
      +FirDiagnosticsDirectives.FIR_DUMP
      +JvmEnvironmentConfigurationDirectives.FULL_JDK
      // :api is compiled with the build's JDK 17 toolchain; its inline functions (codecFor)
      // cannot be inlined into a lower target.
      JvmEnvironmentConfigurationDirectives.JVM_TARGET with JvmTarget.JVM_17

      +CodegenTestDirectives.IGNORE_DEXING // Avoids loading R8 from the classpath.
    }

    configurePlugin()
    configureRecordsRuntime()
  }
}
