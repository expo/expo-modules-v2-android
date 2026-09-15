package io.github.expo.modules.v2.compiler.handlers

import io.github.expo.modules.v2.compiler.EventBindingOrigin
import io.github.expo.modules.v2.compiler.ModuleDiscoveryOrigin
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.util.CustomKotlinLikeDumpStrategy
import org.jetbrains.kotlin.ir.util.FakeOverridesStrategy
import org.jetbrains.kotlin.ir.util.KotlinLikeDumpOptions
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.test.backend.handlers.AbstractIrHandler
import org.jetbrains.kotlin.test.backend.handlers.IrTextDumpHandler.Companion.computeDumpExtension
import org.jetbrains.kotlin.test.backend.handlers.IrTextDumpHandler.Companion.groupWithTestFiles
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.DUMP_KT_IR
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.EXTERNAL_FILE
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.SKIP_KT_DUMP
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.model.BackendKind
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.utils.MultiModuleInfoDumper
import org.jetbrains.kotlin.test.utils.withExtension
import org.jetbrains.kotlin.utils.Printer

/**
 * Dumps the plugin-generated IR as Kotlin-like source into `*.fir.generated.kt.txt`.
 *
 * It runs beside the stock `IrPrettyKotlinDumpHandler`, which writes the whole file to
 * `*.fir.kt.txt`. That handler is final and hardcodes its dump options, so the narrowed view is a
 * separate handler rather than a flag. Two things differ from the stock dump:
 *  - only declarations the plugin generated are printed, so the record's own data-class members
 *    and the test's `box()` stay out;
 *  - the rendered text goes through [tidy], which drops noise the dump options cannot suppress.
 */
class RecordKotlinLikeDumpHandler(
  testServices: TestServices,
  artifactKind: BackendKind<IrBackendInput>,
) : AbstractIrHandler(testServices, artifactKind) {
  private val dumper = MultiModuleInfoDumper("// MODULE: %s")

  override val directiveContainers: List<DirectivesContainer>
    get() = listOf(CodegenTestDirectives)

  override fun processModule(module: TestModule, info: IrBackendInput) {
    if (DUMP_KT_IR !in module.directives || SKIP_KT_DUMP in module.directives) return

    val irFiles = info.irModuleFragment.files
      .groupWithTestFiles(testServices, ordered = true)
      .filterNot { (owner, _) ->
        val testFile = owner?.second ?: return@filterNot false
        owner.first != module || EXTERNAL_FILE in testFile.directives || testFile.isAdditional
      }
      .map { it.second }

    val options = KotlinLikeDumpOptions(
      customDumpStrategy = GeneratedDeclarationsOnly,
      printFileName = irFiles.size > 1 || testServices.moduleStructure.modules.size > 1,
      printFilePath = false,
      printFakeOverridesStrategy = FakeOverridesStrategy.NONE,
      normalizeNames = true,
      stableOrder = true,
      inferElseBranches = true,
    )

    val builder = dumper.builderForModule(module.name)
    for (irFile in irFiles) {
      builder.append(irFile.dumpKotlinLike(options).tidy())
    }
  }

  override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
    val expectedFile = testServices.moduleStructure.originalTestDataFiles.first()
      .withExtension(computeDumpExtension(testServices, DUMP_EXTENSION))

    if (dumper.isEmpty()) {
      assertions.assertFileDoesntExist(expectedFile) {
        "Nothing was dumped, but ${expectedFile.name} exists. Remove it or enable $DUMP_KT_IR."
      }
    } else {
      assertions.assertEqualsToFile(expectedFile, dumper.generateResultingDump())
    }
  }

  companion object {
    const val DUMP_EXTENSION = "generated.kt.txt"
  }
}

/**
 * Prints a declaration only when the plugin generated it, or when it holds one that it did.
 * Classes always print: they are the containers the generated members live in. An `@Event`
 * property is the user's, but the plugin rewrote its initializer, so it prints as well - and so does
 * a function or property whose body held a `discoveredExpoModules()` call the plugin replaced.
 */
private object GeneratedDeclarationsOnly : CustomKotlinLikeDumpStrategy {
  override fun willPrintElement(
    element: IrElement,
    container: IrDeclaration?,
    printer: Printer,
    options: KotlinLikeDumpOptions,
  ): Boolean = element !is IrDeclaration || element is IrClass || element.isLocal() ||
    element.isPluginGenerated()
}

/**
 * A declaration inside a body - a lambda's function, a local variable. It is only visited when its
 * container printed, so it prints too; hiding it would leave the call it belongs to half-rendered.
 */
private fun IrDeclaration.isLocal(): Boolean =
  parent.let { it !is IrClass && it !is IrPackageFragment }

private fun IrDeclaration.isPluginGenerated(): Boolean {
  if (isEventBinding() || callsDiscoveredModules()) {
    return true
  }
  var declaration: IrDeclaration? = this
  while (declaration != null) {
    if (declaration.origin is IrDeclarationOrigin.GeneratedByPlugin) {
      return true
    }
    declaration = declaration.parent as? IrDeclaration
  }
  return false
}

/**
 * Whether this is an `@Event` property whose initializer the plugin wrapped, or one of its parts.
 * The accessor counts too, so the property renders exactly as the stock dump renders it; [tidy]
 * then drops the bare `get` line.
 */
private fun IrDeclaration.isEventBinding(): Boolean {
  val field = when (this) {
    is IrProperty -> backingField
    is IrField -> this
    is IrSimpleFunction -> correspondingPropertySymbol?.owner?.backingField
    else -> null
  } ?: return false
  return (field.initializer?.expression as? IrCall)?.origin == EventBindingOrigin
}

/**
 * Whether this declaration's body holds the `listOf(...)` the plugin put where a
 * `discoveredExpoModules()` call was. The call is the user's, so the marked replacement is the only
 * trace of the plugin; the whole declaration prints so the dump shows the list in place.
 */
private fun IrDeclaration.callsDiscoveredModules(): Boolean {
  if (this !is IrDeclarationBase || this is IrClass) {
    return false
  }
  var found = false
  acceptChildrenVoid(object : IrVisitorVoid() {
    override fun visitElement(element: IrElement) {
      if (found) return
      // Nested classes are printed on their own; their bodies do not make this one print.
      if (element is IrClass) return
      if (element is IrCall && element.origin == ModuleDiscoveryOrigin) {
        found = true
        return
      }
      element.acceptChildrenVoid(this)
    }
  })
  return found
}

private val DEFAULT_ACCESSOR = Regex("""\n[ \t]*(override )?[gs]et(?=\n)""")
private val BLANK_LINES = Regex("""\n([ \t]*\n)+""")
private val BLANK_LINE_BEFORE_BRACE = Regex("""\n[ \t]*\n(?=[ \t]*\})""")

/**
 * Removes rendering noise that no [KotlinLikeDumpOptions] flag controls: the bare `get`/`set`
 * lines of default accessors, and blank-line runs. An accessor with a real body prints its braces,
 * so it survives [DEFAULT_ACCESSOR].
 */
private fun String.tidy(): String = this
  .replace(DEFAULT_ACCESSOR, "")
  .replace(BLANK_LINES, "\n\n")
  .replace(BLANK_LINE_BEFORE_BRACE, "\n")
