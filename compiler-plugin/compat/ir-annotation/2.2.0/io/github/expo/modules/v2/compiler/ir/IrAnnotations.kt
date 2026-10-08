package io.github.expo.modules.v2.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrMutableAnnotationContainer
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.types.IrType

/** Adds an annotation without arguments. Kotlin 2.2.0 - 2.3.21: an annotation is a constructor call. */
internal fun IrMutableAnnotationContainer.addAnnotation(type: IrType, constructor: IrConstructorSymbol) {
  annotations += IrSyntheticConstructorCallImpl(type = type, symbol = constructor)
}
