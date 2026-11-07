package io.github.expo.modules.v2.compiler.ir

import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.kotlinFqName

/**
 * Lambda types as IR spells them: `(A) -> R` is `kotlin.Function1<A, R>`, and `suspend (A) -> R`
 * is `kotlin.coroutines.SuspendFunction1<A, R>`, whose arguments leave out the continuation.
 */
internal object FunctionTypes {
  private val FUNCTION = Regex("kotlin\\.Function\\d+")
  private val SUSPEND_FUNCTION = Regex("kotlin\\.coroutines\\.SuspendFunction\\d+")

  fun isFunction(fqName: String): Boolean = FUNCTION.matches(fqName) || isSuspendFunction(fqName)

  fun isSuspendFunction(fqName: String): Boolean = SUSPEND_FUNCTION.matches(fqName)

  fun isSuspendFunction(type: IrType): Boolean =
    type.classOrNull?.owner?.kotlinFqName?.asString()?.let(::isSuspendFunction) == true
}
