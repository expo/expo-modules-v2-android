package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.hasAnnotation

/** The constructor JavaScript's `new` calls, with a transport settled for each argument. */
internal class ExportedConstructor(
  val constructor: IrConstructor,
  val arguments: List<ValuePlan>,
)

/** A shared class as `ModuleBuilder.sharedClass` wants it described. */
internal class ExportedSharedClass(
  val jsName: String,
  val sharedClass: IrClass,
  val arguments: List<ValuePlan>,
) {
  val trampolineName: String
    get() = Identifiers.Literals.CONSTRUCTOR_TRAMPOLINE
}

internal class SharedClassExports(private val policy: TransportPolicy) {
  fun constructorOf(sharedClass: IrClass): ExportedConstructor? {
    val all = sharedClass.constructors.toList()
    val marked = all.firstOrNull { it.hasAnnotation(Identifiers.FqNames.JS_ANNOTATION) }
    if (marked != null) {
      return ExportedConstructor(marked, argumentsOf(sharedClass, marked))
    }

    val singleConstructor = all.singleOrNull()?.takeIf { it.visibility == DescriptorVisibilities.PUBLIC }
      ?: return null

    val crossable = singleConstructor.parameters
      .filter { it.kind == IrParameterKind.Regular }
      .all { policy.supports(it.type) }

    if (!crossable) {
      return null
    }

    return ExportedConstructor(singleConstructor, argumentsOf(sharedClass, singleConstructor))
  }

  /** The class as a module would describe it, or null when `new` cannot reach it. */
  fun describe(sharedClass: IrClass): ExportedSharedClass? {
    val exported = constructorOf(sharedClass)
      ?: return null
    val annotation = sharedClass.getAnnotation(Identifiers.FqNames.SHARED_OBJECT_ANNOTATION)

    return ExportedSharedClass(
      jsName = annotation
        .stringArgument(Identifiers.Names.ARG_NAME)
        ?.takeIf { it.isNotEmpty() }
        ?: sharedClass.name.asString(),
      sharedClass = sharedClass,
      arguments = exported.arguments,
    )
  }

  private fun argumentsOf(sharedClass: IrClass, constructor: IrConstructor): List<ValuePlan> {
    val classChoice = sharedClass.ownBufferChoice()

    return constructor.parameters
      .filter { it.kind == IrParameterKind.Regular }
      .map { parameter ->
        policy.plan(
          parameter.type,
          parameter.ownBufferChoice().orElse(classChoice),
          Crossing.INBOUND,
        )
      }
  }
}
