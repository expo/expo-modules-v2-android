package io.github.expo.modules.v2.compiler

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin

object RecordKey : GeneratedDeclarationKey()

/** Marks everything this transformer adds, so a box-test dump can single it out. */
object JSModuleKey : GeneratedDeclarationKey()

/**
 * Marks the `EventSupport.bind(...)` call the plugin wraps an `@Event` property's initializer in.
 * The property itself is the user's, so the call carries the mark that lets a box-test dump show
 * what the plugin changed.
 */
object EventBindingOrigin : IrStatementOrigin {
  override val debugName: String
    get() = "EVENT_BINDING"

  override fun toString(): String = debugName
}
