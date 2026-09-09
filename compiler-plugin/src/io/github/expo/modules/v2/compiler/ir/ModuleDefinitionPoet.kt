package io.github.expo.modules.v2.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.patchDeclarationParents

/**
 * Writes the body of `define$ExpoModulesV2`.
 *
 * ```
 * fun define$ExpoModulesV2(builder: ModuleBuilder): String? {
 *   builder.function("add", AnyType(TypeDescriptor.Int, false), ..., returns = ..., methodName = "add")
 *   builder.property("count", AnyType(CommonDescriptors.INT_BOXED_NULL, false), true, "count")
 *   builder.event("changed", AnyType(TypeDescriptor.Int, false))
 *   return "MathUtils"
 * }
 * ```
 */
internal class ModuleDefinitionPoet(
  private val context: IrPluginContext,
  private val symbols: SymbolFinder,
  private val poet: TypeDescriptorPoet,
) {
  private val irBuiltIns = context.irBuiltIns

  fun fill(
    define: IrSimpleFunction,
    owner: IrClass,
    jsName: String,
    exported: List<Exported>,
    sharedClasses: List<ExportedSharedClass> = emptyList(),
  ) {
    val builder = define.parameters.single { it.kind == IrParameterKind.Regular }

    val body = context.irFactory.createSyntheticBlockBody()
    for (export in exported) {
      body.statements += when (export) {
        is ExportedFunction -> declareFunction(export, builder.get())
        is ExportedProperty -> declareProperty(export, builder.get())
        is ExportedEvent -> declareEvent(export, builder.get())
      }
    }
    for (sharedClass in sharedClasses) {
      body.statements += declareSharedClass(sharedClass, builder.get())
    }
    body.statements += IrSyntheticReturnImpl(
      type = irBuiltIns.nothingType,
      returnTargetSymbol = define.symbol,
      value = poet.string(jsName),
    )
    define.body = body
    define.patchDeclarationParents(owner)
  }

  private fun declareFunction(export: ExportedFunction, builder: IrExpression): IrExpression {
    val anyTypeType = symbols.classes.anyType.owner.defaultType
    val arguments = mutableListOf(
      poet.string(export.jsName),
      IrSyntheticVarargImpl(
        type = irBuiltIns.arrayClass.typeWith(anyTypeType),
        varargElementType = anyTypeType,
        elements = export.arguments.map(::anyTypeOf),
      ),
      anyTypeOf(export.result),
      poet.string(methodNameOf(export)),
    )

    if (export.isAsync) {
      arguments += poet.boolean(true)
    }

    return callOn(
      function = symbols.functions.builderFunction,
      receiver = builder,
      arguments = arguments,
      returnType = irBuiltIns.unitType,
    )
  }

  /** `builder.sharedClass("Klass", Klass::class.java, AnyType(...), ...)` */
  private fun declareSharedClass(
    export: ExportedSharedClass,
    builder: IrExpression,
  ): IrExpression {
    val anyTypeType = symbols.classes.anyType.owner.defaultType
    return callOn(
      function = symbols.functions.builderSharedClass,
      receiver = builder,
      arguments = listOf(
        poet.string(export.jsName),
        poet.javaClass(export.sharedClass.defaultType),
        IrSyntheticVarargImpl(
          type = irBuiltIns.arrayClass.typeWith(anyTypeType),
          varargElementType = anyTypeType,
          elements = export.arguments.map(::anyTypeOf),
        ),
        poet.string(export.trampolineName),
      ),
      returnType = irBuiltIns.unitType,
    )
  }

  private fun declareProperty(export: ExportedProperty, builder: IrExpression): IrExpression =
    callOn(
      function = symbols.functions.builderProperty,
      receiver = builder,
      arguments = listOf(
        poet.string(export.jsName),
        anyTypeOf(export.getterPlan),
        poet.boolean(export.property.isVar),
        poet.string(propertyNameOf(export)),
        anyTypeOf(export.setterPlan ?: export.getterPlan),
      ),
      returnType = irBuiltIns.unitType,
    )

  /** `builder.event("changed", AnyType(...))` */
  private fun declareEvent(export: ExportedEvent, builder: IrExpression): IrExpression =
    callOn(
      function = symbols.functions.builderEvent,
      receiver = builder,
      arguments = listOf(poet.string(export.jsName), anyTypeOf(export.payload)),
      returnType = irBuiltIns.unitType,
    )

  /** `AnyType(descriptor, useBuffer)` */
  private fun anyTypeOf(plan: ValuePlan): IrExpression = poet.anyTypeOf(plan)

  /** The JVM method the bridge resolves: the user's own when no trampoline stands in front of it. */
  private fun methodNameOf(export: ExportedFunction): String =
    export.trampolineName ?: export.function.name.asString()

  /** `ModuleBuilder` derives the JVM accessor names from this, including the `is` prefix rule. */
  private fun propertyNameOf(export: ExportedProperty): String =
    export.trampolineBase ?: export.property.name.asString()
}
