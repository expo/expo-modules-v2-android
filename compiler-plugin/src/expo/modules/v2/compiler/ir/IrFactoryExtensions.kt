package expo.modules.v2.compiler.ir

import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.ir.builders.declarations.IrFieldBuilder
import org.jetbrains.kotlin.ir.builders.declarations.IrFunctionBuilder
import org.jetbrains.kotlin.ir.builders.declarations.IrPropertyBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.name.Name

fun IrFactory.buildSyntheticField(builder: IrFieldBuilder.() -> Unit) =
  buildField {
    startOffset = SYNTHETIC_OFFSET
    endOffset = SYNTHETIC_OFFSET
    builder()
  }

fun IrFactory.createSyntheticField(
  origin: IrDeclarationOrigin,
  name: Name,
  visibility: DescriptorVisibility,
  symbol: IrFieldSymbol,
  type: IrType,
  isFinal: Boolean,
  isStatic: Boolean,
  isExternal: Boolean,
) = createField(
  startOffset = SYNTHETIC_OFFSET,
  endOffset = SYNTHETIC_OFFSET,
  origin = origin,
  name = name,
  visibility = visibility,
  symbol = symbol,
  type = type,
  isFinal = isFinal,
  isStatic = isStatic,
  isExternal = isExternal,
)

fun IrFactory.buildSyntheticProperty(builder: IrPropertyBuilder.() -> Unit) =
  buildProperty {
    startOffset = SYNTHETIC_OFFSET
    endOffset = SYNTHETIC_OFFSET
    builder()
  }

fun IrProperty.addSyntheticGetter(builder: IrFunctionBuilder.() -> Unit = {}) =
  addGetter {
    startOffset = SYNTHETIC_OFFSET
    endOffset = SYNTHETIC_OFFSET
    builder()
  }

fun IrFactory.buildSyntheticFun(builder: IrFunctionBuilder.() -> Unit) =
  buildFun {
    startOffset = SYNTHETIC_OFFSET
    endOffset = SYNTHETIC_OFFSET
    builder()
  }

fun IrClass.addSyntheticFunction(builder: IrFunctionBuilder.() -> Unit) =
  addFunction {
    startOffset = SYNTHETIC_OFFSET
    endOffset = SYNTHETIC_OFFSET
    builder()
  }

fun buildSyntheticVariable(
  parent: IrDeclarationParent?,
  origin: IrDeclarationOrigin,
  name: Name,
  type: IrType,
  isVar: Boolean = false,
  isConst: Boolean = false,
  isLateinit: Boolean = false,
): IrVariable =
  buildVariable(
    parent = parent,
    startOffset = SYNTHETIC_OFFSET,
    endOffset = SYNTHETIC_OFFSET,
    origin = origin,
    name = name,
    type = type,
    isVar = isVar,
    isConst = isConst,
    isLateinit = isLateinit,
  )

fun IrFactory.createSyntheticExpressionBody(expression: IrExpression) =
  createExpressionBody(
    SYNTHETIC_OFFSET,
    SYNTHETIC_OFFSET,
    expression
  )

fun IrFactory.createSyntheticBlockBody() =
  createBlockBody(
    SYNTHETIC_OFFSET,
    SYNTHETIC_OFFSET,
  )
