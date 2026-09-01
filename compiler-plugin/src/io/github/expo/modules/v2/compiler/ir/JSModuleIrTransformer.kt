package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.BufferChoice
import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.JSModuleKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.utils.addToStdlib.ifTrue

/**
 * Generates a `@JS` module's registration and its trampolines
 */
class JSModuleIrTransformer(
  private val context: IrPluginContext,
  private val symbols: SymbolFinder,
  private val poet: TypeDescriptorPoet,
) : IrVisitorVoid() {
  private val irBuiltIns = context.irBuiltIns
  private val policy = TransportPolicy(context)
  private val trampolines = TrampolinePoet(context, symbols, poet)

  private sealed interface Exported {
    val jsName: String
    val needsTrampoline: Boolean
  }

  private class ExportedFunction(
    override val jsName: String,
    val function: IrSimpleFunction,
    val arguments: List<ValuePlan>,
    val result: ValuePlan,
  ) : Exported {
    var trampolineName: String? = null

    val isAsync: Boolean
      get() = function.isSuspend

    override val needsTrampoline: Boolean
      get() = isAsync || (arguments + result).any { it.buffered || !it.passthrough }
  }

  private class ExportedProperty(
    override val jsName: String,
    val property: IrProperty,
    val getterPlan: ValuePlan,
    val setterPlan: ValuePlan?,
  ) : Exported {
    var trampolineBase: String? = null

    override val needsTrampoline: Boolean
      get() = listOfNotNull(getterPlan, setterPlan).any { it.buffered || !it.passthrough }
  }

  override fun visitElement(element: IrElement) {
    element.acceptChildrenVoid(this)
  }

  override fun visitClass(declaration: IrClass) {
    declaration.acceptChildrenVoid(this)

    val annotation = declaration.getAnnotation(Identifiers.FqNames.JS_ANNOTATION) ?: return
    generate(declaration, annotation)
  }

  private fun generate(moduleClass: IrClass, annotation: IrConstructorCall) {
    val classChoice = annotation.bufferChoice(Identifiers.Names.ARG_BUFFER)
    val exports = exportedOf(moduleClass, classChoice)
    val jsName = annotation.stringArgument(Identifiers.Names.ARG_NAME)
      ?.takeIf { it.isNotEmpty() }
      ?: moduleClass.name.asString()

    generateTrampolines(moduleClass, exports)
    generateDefine(moduleClass, jsName, exports)
  }

  private fun generateTrampolines(moduleClass: IrClass, exported: List<Exported>) {
    exported
      .filter { e -> e.needsTrampoline }
      .forEach { e ->
        when (e) {
          is ExportedFunction -> {
            val name = e.function.name.asString() + Identifiers.Literals.TRAMPOLINE_SUFFIX
            trampolines.functionTrampoline(
              moduleClass = moduleClass,
              name = name,
              target = e.function,
              arguments = e.arguments,
              result = e.result
            )
            e.trampolineName = name
          }

          is ExportedProperty -> {
            val base = e.property.name.asString().removeIsPrefix()
            val suffix = base.replaceFirstChar { it.titlecase() } + Identifiers.Literals.TRAMPOLINE_SUFFIX
            trampolines.propertyGetter(
              moduleClass = moduleClass,
              name = "get$suffix",
              property = e.property,
              plan = e.getterPlan,
            )
            e.setterPlan?.let { setterPlan ->
              trampolines.propertySetter(
                moduleClass = moduleClass,
                name = "set$suffix",
                property = e.property,
                plan = setterPlan,
              )
            }
            e.trampolineBase = base + Identifiers.Literals.TRAMPOLINE_SUFFIX
          }
        }
      }
  }

  /** Mirrors `ModuleBuilder`'s rule: `isReady` accessors are `isReady`/`setReady`, not `getIsReady`. */
  private fun String.removeIsPrefix(): String =
    if (length > 2 && startsWith("is") && !this[2].isLowerCase()) {
      substring(2).replaceFirstChar { it.lowercase() }
    } else {
      this
    }

  private fun exportedOf(moduleClass: IrClass, classChoice: BufferChoice): List<Exported> =
    moduleClass
      .declarations
      .mapNotNull { declaration ->
        when (declaration) {
          is IrSimpleFunction ->
            // An accessor carries its property's annotation; the property is the export, not the pair.
            if (declaration.correspondingPropertySymbol != null) {
              null
            } else {
              declaration.getAnnotation(Identifiers.FqNames.JS_ANNOTATION)?.let { annotation ->
                makeExportedFunction(declaration, annotation, classChoice)
              }
            }

          is IrProperty ->
            declaration.getAnnotation(Identifiers.FqNames.JS_ANNOTATION)?.let { annotation ->
              makeExportedProperty(declaration, annotation, classChoice)
            }

          else -> null
        }
      }

  private fun makeExportedFunction(
    function: IrSimpleFunction,
    annotation: IrConstructorCall,
    classChoice: BufferChoice,
  ): ExportedFunction {
    val memberChoice = annotation
      .bufferChoice(Identifiers.Names.ARG_BUFFER)
      .orElse(classChoice)

    val returnChoice = annotation
      .bufferChoice(Identifiers.Names.ARG_RETURN_BUFFER)
      .orElse(memberChoice)

    val arguments = function.parameters
      .filter { it.kind == IrParameterKind.Regular }
      .map { parameter ->
        val own = parameter
          .getAnnotation(Identifiers.FqNames.JS_ANNOTATION)
          .bufferChoice(Identifiers.Names.ARG_BUFFER)
        policy.plan(parameter.type, own.orElse(memberChoice), Crossing.INBOUND)
      }

    return ExportedFunction(
      jsName = annotation
        .stringArgument(Identifiers.Names.ARG_NAME)
        ?.takeIf { it.isNotEmpty() }
        ?: function.name.asString(),
      function = function,
      arguments = arguments,
      result = policy.plan(function.returnType, returnChoice, Crossing.RESULT),
    )
  }

  private fun makeExportedProperty(
    property: IrProperty,
    annotation: IrConstructorCall,
    classChoice: BufferChoice,
  ): ExportedProperty {
    val getter = property.getter
      ?: error("@JS: ${"${property.name} has no getter"}")

    val choice = annotation
      .bufferChoice(Identifiers.Names.ARG_BUFFER)
      .orElse(classChoice)

    val returnChoice = annotation
      .bufferChoice(Identifiers.Names.ARG_RETURN_BUFFER)
      .orElse(choice)

    val type = getter.returnType

    return ExportedProperty(
      jsName = annotation
        .stringArgument(Identifiers.Names.ARG_NAME)
        ?.takeIf { it.isNotEmpty() }
        ?: property.name.asString(),
      property = property,
      getterPlan = policy.plan(type, returnChoice, Crossing.RESULT),
      setterPlan = property.isVar.ifTrue { policy.plan(type, choice, Crossing.INBOUND) },
    )
  }

  /**
   * Creates `define$ExpoModulesV2`
   */
  private fun makeDefineFunction(moduleClass: IrClass): IrSimpleFunction {
    val define = moduleClass
      .declarations
      .filterIsInstance<IrSimpleFunction>()
      .firstOrNull { it.name == Identifiers.Names.DEFINE_FUNCTION }
      ?: error(
        "@JS: ${
          "${moduleClass.kotlinFqName} has no inherited ${Identifiers.Literals.DEFINE_FUNCTION}; " +
            "the frontend should have rejected a @JS class that is not a Module"
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

  /**
   * ```
   * override fun define$ExpoModulesV2(builder: ModuleBuilder): String? {
   *   builder.function("add", AnyType(TypeDescriptor.Int, false), ..., returns = ..., methodName = "add")
   *   builder.property("count", AnyType(CommonDescriptors.INT_BOXED_NULL, false), true, "count")
   *   return "MathUtils"
   * }
   * ```
   */
  private fun generateDefine(moduleClass: IrClass, jsName: String, exported: List<Exported>) {
    val define = makeDefineFunction(moduleClass)
    val builder = define.parameters.single { it.kind == IrParameterKind.Regular }

    val body = context.irFactory.createSyntheticBlockBody()
    for (e in exported) {
      body.statements += when (e) {
        is ExportedFunction -> declareFunction(e, builder.get())
        is ExportedProperty -> declareProperty(e, builder.get())
      }
    }
    body.statements += IrSyntheticReturnImpl(
      type = irBuiltIns.nothingType,
      returnTargetSymbol = define.symbol,
      value = poet.string(jsName),
    )
    define.body = body
    define.patchDeclarationParents(moduleClass)
  }

  private fun declareFunction(exportedFunction: ExportedFunction, builder: IrExpression): IrExpression {
    val anyTypeType = symbols.classes.anyType.owner.defaultType
    val arguments = mutableListOf(
      poet.string(exportedFunction.jsName),
      IrSyntheticVarargImpl(
        type = irBuiltIns.arrayClass.typeWith(anyTypeType),
        varargElementType = anyTypeType,
        elements = exportedFunction.arguments.map(::anyTypeOf),
      ),
      anyTypeOf(exportedFunction.result),
      poet.string(methodNameOf(exportedFunction)),
    )

    if (exportedFunction.isAsync) {
      arguments += poet.boolean(true)
    }

    return callOn(
      function = symbols.functions.builderFunction,
      receiver = builder,
      arguments = arguments,
      returnType = irBuiltIns.unitType,
    )
  }

  private fun declareProperty(exportProperty: ExportedProperty, builder: IrExpression): IrExpression =
    callOn(
      function = symbols.functions.builderProperty,
      receiver = builder,
      arguments = listOf(
        poet.string(exportProperty.jsName),
        anyTypeOf(exportProperty.getterPlan),
        poet.boolean(exportProperty.property.isVar),
        poet.string(propertyNameOf(exportProperty)),
        anyTypeOf(exportProperty.setterPlan ?: exportProperty.getterPlan),
      ),
      returnType = irBuiltIns.unitType,
    )

  /** `AnyType(descriptor, useBuffer)` */
  private fun anyTypeOf(plan: ValuePlan): IrExpression =
    poet.constructorCall(
      constructor = symbols.constructors.anyType,
      type = symbols.classes.anyType.owner.defaultType,
      arguments = listOf(poet.descriptorFor(plan.type), poet.boolean(plan.buffered)),
    )

  /** The JVM method the bridge resolves: the user's own when no trampoline stands in front of it. */
  private fun methodNameOf(export: ExportedFunction): String =
    export.trampolineName ?: export.function.name.asString()

  /** `ModuleBuilder` derives the JVM accessor names from this, including the `is` prefix rule. */
  private fun propertyNameOf(export: ExportedProperty): String =
    export.trampolineBase ?: export.property.name.asString()
}
