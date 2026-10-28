package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.declarations.name
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties

/** One variant of an `@Union` type, and what it crosses as. */
internal class UnionVariant(
  val variantClass: IrClass,
  val form: Form,
  /** The JavaScript name of an `object` variant, or the discriminator value of a record one. */
  val tag: String,
  /** The one property a [Form.VALUE] variant crosses as. */
  val property: IrProperty? = null,
) {
  enum class Form {
    /** A class with one constructor property: it crosses as that property's value. */
    VALUE,

    /** An `@Record` class: it crosses as itself. */
    RECORD,

    /** An `object`: it crosses as its [tag], with no value. */
    OBJECT,
  }

  /** The type of the value this variant crosses as, or null for an `object`. */
  val valueType: IrType?
    get() = when (form) {
      Form.VALUE -> property?.getter?.returnType
      Form.RECORD -> variantClass.defaultType
      Form.OBJECT -> null
    }
}

/**
 * The variants of [unionClass], in the order they are declared - the frontend lists sealed
 * subclasses by name, and error messages read better in source order. A subclass of a shape the
 * frontend rejected is left out: the compilation has already failed by then.
 */
internal fun unionVariants(unionClass: IrClass): List<UnionVariant> =
  unionClass.sealedSubclasses
    .map { it.owner }
    // By file name, not path: the frontend checks the same order, and only knows the name.
    .sortedWith(compareBy({ it.fileOrNull?.name.orEmpty() }, { it.startOffset }))
    .mapNotNull { variant ->
      val tag = variant
        .getAnnotation(Identifiers.FqNames.TAG_ANNOTATION)
        .stringArgument(Identifiers.Names.ARG_VALUE)
        ?: variant.name.asString()

      when {
        variant.kind == ClassKind.OBJECT -> UnionVariant(variant, UnionVariant.Form.OBJECT, tag)
        variant.hasAnnotation(Identifiers.Classes.RecordAnnotation) ->
          UnionVariant(variant, UnionVariant.Form.RECORD, tag)

        else -> {
          val parameter = variant.primaryConstructor
            ?.parameters
            ?.singleOrNull { it.kind == IrParameterKind.Regular }
            ?: return@mapNotNull null
          val property = variant.properties.firstOrNull { it.name == parameter.name }
            ?: return@mapNotNull null
          UnionVariant(variant, UnionVariant.Form.VALUE, tag, property)
        }
      }
    }
