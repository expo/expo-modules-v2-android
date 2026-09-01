@file:Suppress("FunctionName")

package io.github.expo.modules.v2.compiler.ir

import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDelegatingConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTryImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.Name

fun IrSyntheticGetFieldImpl(
  symbol: IrFieldSymbol,
  type: IrType,
  receiver: IrExpression?,
  origin: IrStatementOrigin? = null,
  superQualifierSymbol: IrClassSymbol? = null,
) = IrGetFieldImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  symbol = symbol,
  type = type,
  receiver = receiver,
  origin = origin,
  superQualifierSymbol = superQualifierSymbol
)

fun IrSyntheticGetValueImpl(
  type: IrType,
  symbol: IrValueSymbol,
  origin: IrStatementOrigin? = null,
) = IrGetValueImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  type = type,
  symbol = symbol,
  origin = origin,
)

fun IrSyntheticSetValueImpl(
  type: IrType,
  symbol: IrValueSymbol,
  value: IrExpression,
  origin: IrStatementOrigin? = null,
) = IrSetValueImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  type = type,
  symbol = symbol,
  value = value,
  origin = origin,
)

fun IrSyntheticGetObjectValueImpl(
  type: IrType,
  symbol: IrClassSymbol,
) = IrGetObjectValueImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  type = type,
  symbol = symbol,
)

fun IrSyntheticCallImpl(
  type: IrType,
  symbol: IrSimpleFunctionSymbol,
  typeArgumentsCount: Int = 0,
  origin: IrStatementOrigin? = null,
  superQualifierSymbol: IrClassSymbol? = null,
) = IrCallImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  type = type,
  symbol = symbol,
  typeArgumentsCount = typeArgumentsCount,
  origin = origin,
  superQualifierSymbol = superQualifierSymbol,
)

fun IrSyntheticConstructorCallImpl(
  type: IrType,
  symbol: IrConstructorSymbol,
  typeArgumentsCount: Int = 0,
  constructorTypeArgumentsCount: Int = 0,
  origin: IrStatementOrigin? = null,
) = IrConstructorCallImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  type = type,
  symbol = symbol,
  typeArgumentsCount = typeArgumentsCount,
  constructorTypeArgumentsCount = constructorTypeArgumentsCount,
  origin = origin,
)

fun IrSyntheticDelegatingConstructorCallImpl(
  type: IrType,
  symbol: IrConstructorSymbol,
  typeArgumentsCount: Int = 0,
) = IrDelegatingConstructorCallImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  type = type,
  symbol = symbol,
  typeArgumentsCount = typeArgumentsCount,
)

fun IrSyntheticInstanceInitializerCallImpl(
  classSymbol: IrClassSymbol,
  type: IrType,
) = IrInstanceInitializerCallImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  classSymbol = classSymbol,
  type = type,
)

fun IrSyntheticClassReferenceImpl(
  type: IrType,
  symbol: IrClassSymbol,
  classType: IrType,
) = IrClassReferenceImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  type = type,
  symbol = symbol,
  classType = classType,
)

fun IrSyntheticFunctionExpressionImpl(
  type: IrType,
  function: IrSimpleFunction,
  origin: IrStatementOrigin,
) = IrFunctionExpressionImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  type = type,
  function = function,
  origin = origin,
)

fun IrSyntheticVarargImpl(
  type: IrType,
  varargElementType: IrType,
  elements: List<IrExpression>,
) = IrVarargImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  type = type,
  varargElementType = varargElementType,
  elements = elements,
)

fun IrSyntheticReturnImpl(
  type: IrType,
  returnTargetSymbol: IrReturnTargetSymbol,
  value: IrExpression,
) = IrReturnImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  type = type,
  returnTargetSymbol = returnTargetSymbol,
  value = value,
)

fun IrSyntheticBlockImpl(
  type: IrType,
  origin: IrStatementOrigin? = null,
  statements: List<IrStatement> = emptyList(),
) = IrBlockImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  type = type,
  origin = origin,
  statements = statements,
)

fun IrSyntheticTryImpl(type: IrType) = IrTryImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  type = type,
)

fun IrSyntheticWhenImpl(
  type: IrType,
  origin: IrStatementOrigin? = null,
) = IrWhenImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  type = type,
  origin = origin,
)

fun IrSyntheticBranchImpl(
  condition: IrExpression,
  result: IrExpression,
) = IrBranchImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  condition = condition,
  result = result,
)

fun IrSyntheticElseBranchImpl(
  condition: IrExpression,
  result: IrExpression,
) = IrElseBranchImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  condition = condition,
  result = result,
)

fun IrSyntheticTypeOperatorCallImpl(
  type: IrType,
  operator: IrTypeOperator,
  typeOperand: IrType,
  argument: IrExpression,
) = IrTypeOperatorCallImpl(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  type = type,
  operator = operator,
  typeOperand = typeOperand,
  argument = argument,
)

/** The `IrConstImpl` factories the plugin uses, with the offsets pinned. */
object IrSyntheticConstImpl {
  fun string(type: IrType, value: String): IrConst =
    IrConstImpl.string(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type, value)

  fun boolean(type: IrType, value: Boolean): IrConst =
    IrConstImpl.boolean(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type, value)
}

/** Reads a value - a parameter or a local - as an expression. */
internal fun IrValueDeclaration.get(): IrExpression =
  IrSyntheticGetValueImpl(type, symbol)

internal fun objectValue(symbol: IrClassSymbol): IrExpression =
  IrSyntheticGetObjectValueImpl(symbol.owner.defaultType, symbol)

/** A call on an instance [receiver]. */
internal fun callOn(
  function: IrSimpleFunctionSymbol,
  receiver: IrExpression,
  arguments: List<IrExpression> = emptyList(),
  returnType: IrType = function.owner.returnType,
  typeArguments: List<IrType> = emptyList(),
): IrExpression = buildCall(function, receiver, arguments, returnType, typeArguments)

/** A call on an `object`, such as `Trampoline.arguments(...)` or `Bridge.fromJni(...)`. */
internal fun callStatic(
  function: IrSimpleFunctionSymbol,
  owner: IrClassSymbol,
  arguments: List<IrExpression> = emptyList(),
  returnType: IrType = function.owner.returnType,
  typeArguments: List<IrType> = emptyList(),
): IrExpression = buildCall(function, objectValue(owner), arguments, returnType, typeArguments)

private fun buildCall(
  function: IrSimpleFunctionSymbol,
  dispatchReceiver: IrExpression?,
  arguments: List<IrExpression>,
  returnType: IrType,
  typeArguments: List<IrType>,
): IrExpression = IrSyntheticCallImpl(
  returnType, function,
  typeArgumentsCount = typeArguments.size,
).apply {
  typeArguments.forEachIndexed { index, type -> this.typeArguments[index] = type }
  var next = 0
  function.owner.parameters.forEach { parameter ->
    when (parameter.kind) {
      IrParameterKind.DispatchReceiver ->
        this.arguments[parameter.indexInParameters] = dispatchReceiver
      // Trailing parameters the caller did not supply keep a null slot, which is how IR spells
      // "use the declared default". That is what lets a `:api` DSL function grow an optional
      // parameter without every call site here having to name it.
      IrParameterKind.Regular ->
        this.arguments[parameter.indexInParameters] = arguments.getOrNull(next++)

      else -> Unit
    }
  }
}

/** An annotation's `String` argument, or null when it is absent. */
internal fun IrConstructorCall?.stringArgument(name: Name): String? =
  this?.argumentByName(name)?.let { (it as? IrConst)?.value as? String }

internal fun IrConstructorCall.argumentByName(name: Name): IrExpression? {
  val parameter = symbol.owner.parameters.firstOrNull {
    it.kind == IrParameterKind.Regular && it.name == name
  } ?: return null
  return arguments[parameter.indexInParameters]
}

fun IrProperty.generateDefaultPropertyGetter(factory: IrFactory) {
  val getter = getter
    ?: return

  if (getter.body != null) {
    return
  }

  val backingField = backingField
    ?: return

  val receiver = getter.parameters.firstOrNull {
    it.kind == IrParameterKind.DispatchReceiver
  }

  getter.body = factory
    .createSyntheticBlockBody()
    .apply {
      val getBackingField = IrSyntheticGetFieldImpl(
        symbol = backingField.symbol,
        type = backingField.type,
        receiver = receiver?.let {
          IrSyntheticGetValueImpl(
            type = it.type,
            symbol = it.symbol
          )
        }
      )

      statements += IrSyntheticReturnImpl(
        type = getter.returnType,
        returnTargetSymbol = getter.symbol,
        value = getBackingField,
      )
    }
}
