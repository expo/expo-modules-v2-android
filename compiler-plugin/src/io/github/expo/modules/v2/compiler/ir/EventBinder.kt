package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.EventBindingOrigin
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.util.kotlinFqName

/**
 * Binds every `@Event` property to its JavaScript name and payload type by wrapping its initializer:
 *
 * ```
 * val onChanged = event<Change>(...)
 * // becomes
 * val onChanged = EventSupport.bind(event<Change>(...), "changed", type$0, false)
 * ```
 *
 * `event<T>(...)` already ties the event to its owner (it is a member of `ExpoObject`); the wrap
 * adds what only the plugin knows - the name and the transport of the payload - and records the
 * event on its owner so the native side can find it by name. The descriptor is hoisted into a
 * static field, so constructing an instance allocates nothing beyond the event itself.
 */
internal class EventBinder(
  private val symbols: SymbolFinder,
  private val poet: TypeDescriptorPoet,
  private val descriptors: DescriptorFields,
) {
  fun bind(owner: IrClass, events: List<ExportedEvent>) {
    for (export in events) {
      val field = export.property.backingField
        ?: error("@Event: ${owner.kotlinFqName}.${export.property.name} has no backing field")
      val body = field.initializer
        ?: error("@Event: ${owner.kotlinFqName}.${export.property.name} has no initializer")

      val original = body.expression
      val bind = callStatic(
        symbols.functions.bindEvent,
        symbols.classes.eventSupport,
        arguments = listOf(
          original,
          poet.string(export.jsName),
          descriptors.read(owner, export.payload.type),
          poet.boolean(export.payload.buffered),
        ),
        returnType = original.type,
        typeArguments = listOf(export.payload.type),
      ) as IrCall
      bind.origin = EventBindingOrigin
      body.expression = bind
    }
  }
}
