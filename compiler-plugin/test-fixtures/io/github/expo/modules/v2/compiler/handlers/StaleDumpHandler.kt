package io.github.expo.modules.v2.compiler.handlers

import org.jetbrains.kotlin.test.backend.handlers.AbstractIrHandler
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives.FIR_DUMP
import org.jetbrains.kotlin.test.model.BackendKind
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.utils.withExtension

/**
 * Fails a box test that carries a `*.fir.txt` it no longer asks for.
 *
 * Dumps are only made to look at a test locally (see ExpoTestDirectives), and dropping the directive
 * again should clean up after it. The stock IR handlers and [RecordKotlinLikeDumpHandler] do that -
 * they fail when their file exists but nothing was dumped, and the test framework deletes the file -
 * but the FIR dump handler skips the check, so a `*.fir.txt` would be left behind.
 */
class StaleDumpHandler(
  testServices: TestServices,
  artifactKind: BackendKind<IrBackendInput>,
) : AbstractIrHandler(testServices, artifactKind) {
  override fun processModule(module: TestModule, info: IrBackendInput) = Unit

  override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
    val structure = testServices.moduleStructure
    if (FIR_DUMP in structure.allDirectives) return

    val firDump = structure.originalTestDataFiles.first().withExtension("fir.txt")
    assertions.assertFileDoesntExist(firDump) {
      "${firDump.name} exists, but the test does not enable $FIR_DUMP. Remove it or enable $FIR_DUMP."
    }
  }
}
