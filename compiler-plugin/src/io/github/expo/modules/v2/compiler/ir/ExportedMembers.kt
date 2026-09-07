package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.BufferChoice
import io.github.expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.utils.addToStdlib.ifTrue

/** One `@JS` member of an exported class, as the builder will describe it. */
internal sealed interface Exported {
  val jsName: String
  val needsTrampoline: Boolean
}

internal class ExportedFunction(
  override val jsName: String,
  val function: IrSimpleFunction,
  val arguments: List<ValuePlan>,
  val result: ValuePlan,
) : Exported {
  var trampolineName: String? = null

  val isAsync: Boolean
    get() = function.isSuspend

  override val needsTrampoline: Boolean
    get() = isAsync || (arguments + result).any { it.buffered || !it.passthrough }
}

internal class ExportedProperty(
  override val jsName: String,
  val property: IrProperty,
  val getterPlan: ValuePlan,
  val setterPlan: ValuePlan?,
) : Exported {
  var trampolineBase: String? = null

  override val needsTrampoline: Boolean
    get() = listOfNotNull(getterPlan, setterPlan).any { it.buffered || !it.passthrough }
}

/** The `@BufferMode(value)` this declaration carries, or `AUTO` when it carries none. */
internal fun IrAnnotationContainer.ownBufferChoice(): BufferChoice =
  getAnnotation(Identifiers.FqNames.BUFFER_MODE_ANNOTATION)
    .bufferChoice(Identifiers.Names.ARG_VALUE)

/**
 * Reads the `@JS` members off a class and settles how each of their values crosses the bridge.
 *
 * A module and a shared object declare members the same way, so both ask this the same question.
 */
internal class ExportedMembers(private val policy: TransportPolicy) {
  fun of(exportedClass: IrClass): List<Exported> {
    val classChoice = exportedClass.ownBufferChoice()
    return exportedClass
      .declarations
      .mapNotNull { declaration ->
        when (declaration) {
          is IrSimpleFunction ->
            // An accessor carries its property's annotation; the property is the export, not the pair.
            if (declaration.correspondingPropertySymbol != null) {
              null
            } else {
              declaration.getAnnotation(Identifiers.FqNames.JS_ANNOTATION)?.let { annotation ->
                function(declaration, annotation, classChoice)
              }
            }

          is IrProperty ->
            declaration.getAnnotation(Identifiers.FqNames.JS_ANNOTATION)?.let { annotation ->
              property(declaration, annotation, classChoice)
            }

          else -> null
        }
      }
  }

  private fun function(
    function: IrSimpleFunction,
    annotation: IrConstructorCall,
    classChoice: BufferChoice,
  ): ExportedFunction {
    val bufferMode = function.getAnnotation(Identifiers.FqNames.BUFFER_MODE_ANNOTATION)
    val memberChoice = bufferMode
      .bufferChoice(Identifiers.Names.ARG_VALUE)
      .orElse(classChoice)

    val returnChoice = bufferMode
      .bufferChoice(Identifiers.Names.ARG_RETURNS)
      .orElse(memberChoice)

    val arguments = function.parameters
      .filter { it.kind == IrParameterKind.Regular }
      .map { parameter ->
        policy.plan(
          parameter.type,
          parameter.ownBufferChoice().orElse(memberChoice),
          Crossing.INBOUND,
        )
      }

    return ExportedFunction(
      jsName = annotation
        .stringArgument(Identifiers.Names.ARG_NAME)
        ?.takeIf { it.isNotEmpty() }
        ?: function.name.asString(),
      function = function,
      arguments = arguments,
      result = policy.plan(function.returnType, returnChoice, Crossing.RESULT),
    )
  }

  private fun property(
    property: IrProperty,
    annotation: IrConstructorCall,
    classChoice: BufferChoice,
  ): ExportedProperty {
    val getter = property.getter
      ?: error("@JS: ${"${property.name} has no getter"}")

    val bufferMode = property.getAnnotation(Identifiers.FqNames.BUFFER_MODE_ANNOTATION)
    val choice = bufferMode
      .bufferChoice(Identifiers.Names.ARG_VALUE)
      .orElse(classChoice)

    val returnChoice = bufferMode
      .bufferChoice(Identifiers.Names.ARG_RETURNS)
      .orElse(choice)

    val type = getter.returnType

    return ExportedProperty(
      jsName = annotation
        .stringArgument(Identifiers.Names.ARG_NAME)
        ?.takeIf { it.isNotEmpty() }
        ?: property.name.asString(),
      property = property,
      getterPlan = policy.plan(type, returnChoice, Crossing.RESULT),
      setterPlan = property.isVar.ifTrue { policy.plan(type, choice, Crossing.INBOUND) },
    )
  }
}
