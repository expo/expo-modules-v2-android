package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.hasAnnotation

/**
 * Marks [function] with kolibri's `@CalledFromNative`: the bridge resolves it through JNI by the
 * name the module definition records, so R8 must neither rename nor remove it. The library's
 * consumer rules keep exactly what carries this mark, and nothing else of a module.
 */
internal fun SymbolFinder.markCalledFromNative(function: IrSimpleFunction) {
  if (function.hasAnnotation(Identifiers.Classes.CalledFromNativeAnnotation.asSingleFqName())) {
    return
  }
  val annotation = classes.calledFromNative
  function.addAnnotation(annotation.owner.defaultType, annotation.constructors.single())
}
