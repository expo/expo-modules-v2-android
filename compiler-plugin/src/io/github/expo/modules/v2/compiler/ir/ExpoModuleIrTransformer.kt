package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.JSModuleKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isSubclassOf
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Describes every `@ExpoModule` class into the `define$ExpoModulesV2` it inherits from `Module`.
 *
 * A module is registered as an instance, so it overrides that function rather than getting one of
 * its own. Alongside its members it names the shared classes it exposes: those nested inside it,
 * and those listed in `classes = [...]`.
 */
internal class ExpoModuleIrTransformer(
  private val symbols: SymbolFinder,
  context: IrPluginContext,
  poet: TypeDescriptorPoet,
) : IrVisitorVoid() {
  private val policy = TransportPolicy(context, symbols)
  private val members = ExportedMembers(policy)
  private val sharedClasses = SharedClassExports(policy)
  private val trampolines = ExportedTrampolines(context, symbols, poet)
  private val definition = ModuleDefinitionPoet(context, symbols, poet)

  override fun visitElement(element: IrElement) {
    element.acceptChildrenVoid(this)
  }

  override fun visitClass(declaration: IrClass) {
    declaration.acceptChildrenVoid(this)

    val annotation = declaration.getAnnotation(Identifiers.FqNames.EXPO_MODULE_ANNOTATION)
      ?: return

    val exported = members.of(declaration)
    trampolines.generate(declaration, exported)
    definition.fill(
      define = inheritedDefine(declaration),
      owner = declaration,
      jsName = jsNameOf(declaration, annotation),
      exported = exported,
      sharedClasses = sharedClassesOf(declaration, annotation),
    )
  }

  private fun jsNameOf(moduleClass: IrClass, annotation: IrConstructorCall): String =
    annotation.stringArgument(Identifiers.Names.ARG_NAME)
      ?.takeIf { it.isNotEmpty() }
      ?: moduleClass.name.asString()

  private fun sharedClassesOf(
    moduleClass: IrClass,
    annotation: IrConstructorCall,
  ): List<ExportedSharedClass> {
    val nested = moduleClass.declarations
      .filterIsInstance<IrClass>()
      .filter { it.isSubclassOf(symbols.classes.sharedObject.owner) }

    val listed = annotation.classReferenceArgument(Identifiers.Names.ARG_CLASSES)

    return (nested + listed)
      .distinct()
      .filter { it.hasAnnotation(Identifiers.FqNames.SHARED_OBJECT_ANNOTATION) }
      .mapNotNull(sharedClasses::describe)
  }

  /** Turns the fake override of `Module.define$ExpoModulesV2` into this module's own. */
  private fun inheritedDefine(moduleClass: IrClass): IrSimpleFunction {
    val define = moduleClass
      .declarations
      .filterIsInstance<IrSimpleFunction>()
      .firstOrNull { it.name == Identifiers.Names.DEFINE_FUNCTION }
      ?: error(
        "@ExpoModule: ${
          "${moduleClass.kotlinFqName} has no inherited ${Identifiers.Literals.DEFINE_FUNCTION}; " +
            "the frontend should have rejected an @ExpoModule class that is not a Module"
        }"
      )

    define.isFakeOverride = false
    define.origin = IrDeclarationOrigin.GeneratedByPlugin(JSModuleKey)
    define.modality = Modality.FINAL
    define.overriddenSymbols = listOf(symbols.functions.define)
    define.parameters
      .single { it.kind == IrParameterKind.DispatchReceiver }
      .type = moduleClass.defaultType
    return define
  }
}
