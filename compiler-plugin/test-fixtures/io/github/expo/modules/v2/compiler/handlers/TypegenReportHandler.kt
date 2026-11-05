package io.github.expo.modules.v2.compiler.handlers

import io.github.expo.modules.v2.compiler.services.typegenOutputOf
import org.jetbrains.kotlin.test.backend.handlers.AbstractIrHandler
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.model.BackendKind
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.utils.MultiModuleInfoDumper
import org.jetbrains.kotlin.test.utils.withExtension

/**
 * Compares the typegen report each module's compilation wrote against `<test>.exports.json`, one
 * `// MODULE:` section per module when the test has several.
 *
 * The report names each declaration's source file by its absolute path, and its duration varies, so
 * both are normalized first: a file by its name alone, a duration to 0.
 */
class TypegenReportHandler(
  testServices: TestServices,
  artifactKind: BackendKind<IrBackendInput>,
) : AbstractIrHandler(testServices, artifactKind) {
  private val dumper = MultiModuleInfoDumper("// MODULE: %s")

  override fun processModule(module: TestModule, info: IrBackendInput) {
    val report = testServices.typegenOutputOf(module)
    assertions.assertTrue(report.isFile) { "The plugin wrote no typegen report for ${module.name}" }
    dumper.builderForModule(module.name).append(normalize(report.readText()))
  }

  override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
    val expectedFile = testServices.moduleStructure.originalTestDataFiles.first()
      .withExtension(DUMP_EXTENSION)
    assertions.assertEqualsToFile(expectedFile, dumper.generateResultingDump())
  }

  private fun normalize(report: String): String = report
    .replace(FILE, "$1$2\"")
    .replace(DURATION, "\"durationMs\": 0")

  companion object {
    const val DUMP_EXTENSION = "exports.json"

    private val FILE = Regex("""("file": ")[^"]*?([^/\\"]*)"""")
    private val DURATION = Regex(""""durationMs": \d+""")
  }
}
