package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass

/**
 * Puts a trampoline in front of every exported member that needs one, and records its name on the
 * export so the description can point the bridge at it.
 *
 * A member needs one when a value has to be converted or moved onto the binary buffer; anything the
 * bridge can hand over untouched is called directly.
 */
internal class ExportedTrampolines(
  context: IrPluginContext,
  symbols: SymbolFinder,
  poet: TypeDescriptorPoet,
  descriptors: DescriptorFields,
) {
  private val trampolines = TrampolinePoet(context, symbols, poet, descriptors)

  fun generate(exportedClass: IrClass, exported: List<Exported>) {
    exported
      .filter { it.needsTrampoline }
      .forEach { export ->
        when (export) {
          is ExportedFunction -> function(exportedClass, export)
          is ExportedProperty -> property(exportedClass, export)
          is ExportedEvent -> Unit
        }
      }
  }

  /** `Klass.__construct$ExpoModulesV2(...)`, which JavaScript's `new` lands on. */
  fun constructor(sharedClass: IrClass, exported: ExportedConstructor) {
    trampolines.constructorTrampoline(
      sharedClass = sharedClass,
      name = Identifiers.Literals.CONSTRUCTOR_TRAMPOLINE,
      constructor = exported.constructor,
      arguments = exported.arguments,
    )
  }

  private fun function(exportedClass: IrClass, export: ExportedFunction) {
    val name = export.function.name.asString() + Identifiers.Literals.TRAMPOLINE_SUFFIX
    trampolines.functionTrampoline(
      moduleClass = exportedClass,
      name = name,
      target = export.function,
      arguments = export.arguments,
      result = export.result,
    )
    export.trampolineName = name
  }

  private fun property(exportedClass: IrClass, export: ExportedProperty) {
    val base = export.property.name.asString().removeIsPrefix()
    val suffix = base.replaceFirstChar { it.titlecase() } + Identifiers.Literals.TRAMPOLINE_SUFFIX

    trampolines.propertyGetter(
      moduleClass = exportedClass,
      name = "get$suffix",
      property = export.property,
      plan = export.getterPlan,
    )
    export.setterPlan?.let { setterPlan ->
      trampolines.propertySetter(
        moduleClass = exportedClass,
        name = "set$suffix",
        property = export.property,
        plan = setterPlan,
      )
    }
    export.trampolineBase = base + Identifiers.Literals.TRAMPOLINE_SUFFIX
  }

  /** Mirrors `ModuleBuilder`'s rule: `isReady` accessors are `isReady`/`setReady`, not `getIsReady`. */
  private fun String.removeIsPrefix(): String =
    if (length > 2 && startsWith("is") && !this[2].isLowerCase()) {
      substring(2).replaceFirstChar { it.lowercase() }
    } else {
      this
    }
}
