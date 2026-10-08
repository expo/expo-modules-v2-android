package io.github.expo.modules.v2.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrMutableAnnotationContainer
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET

/** Adds an annotation without arguments. Kotlin 2.4.0+: an annotation is an `IrAnnotation` of its own. */
internal fun IrMutableAnnotationContainer.addAnnotation(type: IrType, constructor: IrConstructorSymbol) {
  annotations += IrAnnotationImpl.fromSymbolOwner(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type, constructor)
}
