package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.UnionKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.Name

/**
 * Fills every body the FIR extension declared for an `@Union` type:
 *
 * ```
 * indexOf(value) = when { value is A -> 0; value is B -> 1; else -> -1 }
 * unwrap(value)  = when { value is A -> (value as A).property; value is B -> value; else -> null }
 * wrap(index, payload) = when (index) { 0 -> A(payload as T); 1 -> payload as B; 2 -> C; else -> noSuchVariant(...) }
 * ```
 */
class UnionCodecIrTransformer(
  private val context: IrPluginContext,
  private val symbols: SymbolFinder,
  private val poet: TypeDescriptorPoet,
) : IrVisitorVoid() {
  private val irBuiltIns = context.irBuiltIns

  private val policy = TransportPolicy(context, symbols)

  private val descriptors = DescriptorFields(
    context, symbols, poet, UnionKey, isStatic = false,
  )

  override fun visitElement(element: IrElement) {
    element.acceptChildrenVoid(this)
  }

  override fun visitClass(declaration: IrClass) {
    declaration.acceptChildrenVoid(this)

    if (!declaration.hasAnnotation(Identifiers.Classes.UnionAnnotation) ||
      declaration.modality != Modality.SEALED
    ) {
      return
    }

    val codecClass = declaration.declarations
      .filterIsInstance<IrClass>()
      .firstOrNull { it.name == Identifiers.Names.UNION_CODEC_CLASS }
      ?: return

    val companion = declaration.declarations
      .filterIsInstance<IrClass>()
      .firstOrNull { it.isCompanion }
      ?: return

    generate(declaration, codecClass, companion)
  }

  private fun generate(union: IrClass, codecClass: IrClass, companion: IrClass) {
    val variants = unionVariants(union)

    fillUnionClass(union, codecClass)
    fillSchema(union, codecClass, variants)
    fillIndexOf(codecClass, variants)
    fillUnwrap(codecClass, variants)
    fillWrap(union, codecClass, variants)
    fillCodecConstructor(codecClass)
    fillCompanion(codecClass, companion)
  }

  private fun fillUnionClass(union: IrClass, codecClass: IrClass) {
    val property = codecClass.property(Identifiers.Names.UNION_CLASS) ?: return

    property.backingField?.let { field ->
      field.initializer = context.irFactory.createSyntheticExpressionBody(
        poet.javaClass(union.defaultType),
      )
    }

    property.generateDefaultPropertyGetter(context.irFactory)
  }

  private fun fillSchema(union: IrClass, codecClass: IrClass, variants: List<UnionVariant>) {
    val property = codecClass.property(Identifiers.Names.SCHEMA) ?: return
    val backingField = property.backingField ?: return

    val discriminator = union
      .getAnnotation(Identifiers.FqNames.UNION_ANNOTATION)
      .stringArgument(Identifiers.Names.ARG_DISCRIMINATOR)
      ?: ""
    val bufferSafe = variants.all { variant -> variant.valueType?.let(policy::isBufferSafe) ?: true }

    val codecThis = codecClass.thisReceiver
      ?: error("${codecClass.name} has no dispatch receiver")

    val variantType = symbols.classes.unionVariant.owner.defaultType
    val variantExpressions = variants.map { variant ->
      val valueType = variant.valueType
      poet.constructorCall(
        constructor = symbols.constructors.unionVariant,
        type = variantType,
        arguments = listOf(
          poet.string(variant.variantClass.name.asString()),
          poet.string(variant.tag),
          if (valueType != null) {
            descriptors.read(codecClass, valueType, codecThis)
          } else {
            IrSyntheticConstImpl.constNull(symbols.classes.typeDescriptor.owner.defaultType.makeNullable())
          },
        ),
      )
    }

    val schema = poet.constructorCall(
      constructor = symbols.constructors.unionSchema,
      type = symbols.classes.unionSchema.owner.defaultType,
      arguments = listOf(
        // A union crosses as one of its variants, so it carries no name in JavaScript. This one only
        // reaches error messages, where the type's own name is what the reader is looking for.
        poet.string(union.name.asString()),
        poet.string(discriminator),
        poet.boolean(bufferSafe),
        IrSyntheticVarargImpl(
          type = irBuiltIns.arrayClass.typeWith(variantType),
          varargElementType = variantType,
          elements = variantExpressions,
        ),
      ),
    )

    backingField.initializer = context.irFactory.createSyntheticExpressionBody(schema)
    property.generateDefaultPropertyGetter(context.irFactory)
  }

  /** `when { value is A -> 0; ...; else -> -1 }` */
  private fun fillIndexOf(codecClass: IrClass, variants: List<UnionVariant>) {
    val function = codecClass.function(Identifiers.Names.INDEX_OF) ?: return
    val value = function.regularParameters().single()
    val intType = irBuiltIns.intType

    val result = IrSyntheticWhenImpl(intType).apply {
      variants.forEachIndexed { index, variant ->
        branches += IrSyntheticBranchImpl(
          condition = isInstance(value, variant.variantClass),
          result = IrSyntheticConstImpl.int(intType, index),
        )
      }
      branches += elseBranch(IrSyntheticConstImpl.int(intType, -1))
    }

    function.returning(result)
  }

  /** `when { value is A -> (value as A).property; value is B -> value; ...; else -> null }` */
  private fun fillUnwrap(codecClass: IrClass, variants: List<UnionVariant>) {
    val function = codecClass.function(Identifiers.Names.UNWRAP) ?: return
    val value = function.regularParameters().single()
    val anyN = irBuiltIns.anyNType

    val result = IrSyntheticWhenImpl(anyN).apply {
      variants.forEach { variant ->
        val unwrapped = when (variant.form) {
          UnionVariant.Form.VALUE -> {
            val getter = variant.property?.getter ?: return@forEach
            IrSyntheticCallImpl(getter.returnType, getter.symbol).apply {
              arguments[0] = cast(value.get(), variant.variantClass.defaultType)
            }
          }

          UnionVariant.Form.RECORD -> value.get()
          UnionVariant.Form.OBJECT -> return@forEach
        }

        branches += IrSyntheticBranchImpl(
          condition = isInstance(value, variant.variantClass),
          result = unwrapped,
        )
      }
      branches += elseBranch(IrSyntheticConstImpl.constNull(anyN))
    }

    function.returning(result)
  }

  /**
   * `when { index == 0 -> A(payload as T); index == 1 -> payload as B; index == 2 -> C; else ->
   * noSuchVariant("Union", index) }`
   */
  private fun fillWrap(union: IrClass, codecClass: IrClass, variants: List<UnionVariant>) {
    val function = codecClass.function(Identifiers.Names.WRAP) ?: return
    val (index, payload) = function.regularParameters()
    val unionType = union.defaultType

    val result = IrSyntheticWhenImpl(unionType).apply {
      variants.forEachIndexed { at, variant ->
        val variantType = variant.variantClass.defaultType
        val wrapped = when (variant.form) {
          UnionVariant.Form.VALUE -> {
            val constructor = variant.variantClass.primaryConstructor ?: return@forEachIndexed
            val valueType = variant.valueType ?: return@forEachIndexed
            poet.constructorCall(constructor.symbol, variantType, listOf(cast(payload.get(), valueType)))
          }

          UnionVariant.Form.RECORD -> cast(payload.get(), variantType)
          UnionVariant.Form.OBJECT ->
            IrSyntheticGetObjectValueImpl(variantType, variant.variantClass.symbol)
        }

        branches += IrSyntheticBranchImpl(
          condition = IrSyntheticCallImpl(irBuiltIns.booleanType, irBuiltIns.eqeqSymbol).apply {
            arguments[0] = index.get()
            arguments[1] = IrSyntheticConstImpl.int(irBuiltIns.intType, at)
          },
          result = wrapped,
        )
      }

      branches += elseBranch(
        callStaticFunction(
          symbols.functions.noSuchVariant,
          listOf(poet.string(union.name.asString()), index.get()),
        ),
      )
    }

    function.returning(result)
  }

  /**
   * `<init>() { super(); <instance initializers>; UnionRegistry.register(this) }`
   */
  private fun fillCodecConstructor(codecClass: IrClass) {
    val constructor = codecClass.constructors.singleOrNull() ?: return
    val codecThis = codecClass.thisReceiver ?: return

    val body = context.irFactory.createSyntheticBlockBody()
    body.statements += delegatingAnyConstructorCall()
    body.statements += IrSyntheticInstanceInitializerCallImpl(
      classSymbol = codecClass.symbol,
      type = irBuiltIns.unitType,
    )
    body.statements += callOn(
      function = symbols.functions.registerUnion,
      receiver = objectValue(symbols.classes.unionRegistry),
      arguments = listOf(IrSyntheticGetValueImpl(codecThis.type, codecThis.symbol)),
      returnType = irBuiltIns.unitType,
    )
    constructor.body = body
  }

  /**
   * `val unionCodec$ExpoModulesV2 = UnionCodec$ExpoModulesV2()`.
   */
  private fun fillCompanion(codecClass: IrClass, companion: IrClass) {
    companion
      .constructors
      .singleOrNull()
      ?.let { constructor ->
        if (constructor.body == null) {
          constructor.body = context.irFactory.createSyntheticBlockBody().apply {
            statements += delegatingAnyConstructorCall()
            statements += IrSyntheticInstanceInitializerCallImpl(
              classSymbol = companion.symbol,
              type = irBuiltIns.unitType,
            )
          }
        }
      }

    val property = companion.property(Identifiers.Names.UNION_CODEC_PROPERTY) ?: return
    val backingField = property.backingField ?: return
    val codecConstructor = codecClass.constructors.single()

    backingField.initializer = context.irFactory.createSyntheticExpressionBody(
      IrSyntheticConstructorCallImpl(
        type = codecClass.defaultType,
        symbol = codecConstructor.symbol
      ),
    )
    property.generateDefaultPropertyGetter(context.irFactory)
  }

  private fun IrSimpleFunction.returning(value: IrExpression) {
    body = context.irFactory.createSyntheticBlockBody().apply {
      statements += IrSyntheticReturnImpl(
        type = irBuiltIns.nothingType,
        returnTargetSymbol = symbol,
        value = value,
      )
    }
  }

  private fun IrSimpleFunction.regularParameters(): List<IrValueParameter> =
    parameters.filter { it.kind == IrParameterKind.Regular }

  private fun isInstance(value: IrValueParameter, variantClass: IrClass): IrExpression =
    IrSyntheticTypeOperatorCallImpl(
      type = irBuiltIns.booleanType,
      operator = IrTypeOperator.INSTANCEOF,
      typeOperand = variantClass.defaultType,
      argument = value.get(),
    )

  private fun cast(value: IrExpression, type: IrType): IrExpression =
    IrSyntheticTypeOperatorCallImpl(
      type = type,
      operator = IrTypeOperator.CAST,
      typeOperand = type,
      argument = value,
    )

  private fun elseBranch(result: IrExpression) =
    IrSyntheticElseBranchImpl(
      condition = IrSyntheticConstImpl.boolean(irBuiltIns.booleanType, true),
      result = result,
    )

  private fun callStaticFunction(
    function: org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol,
    arguments: List<IrExpression>,
  ): IrExpression = IrSyntheticCallImpl(function.owner.returnType, function).apply {
    arguments.forEachIndexed { at, argument -> this.arguments[at] = argument }
  }

  private fun IrClass.property(name: Name): IrProperty? =
    declarations.filterIsInstance<IrProperty>().firstOrNull { it.name == name }

  private fun IrClass.function(name: Name): IrSimpleFunction? =
    declarations.filterIsInstance<IrSimpleFunction>().firstOrNull { it.name == name }

  private fun delegatingAnyConstructorCall() = IrSyntheticDelegatingConstructorCallImpl(
    type = irBuiltIns.unitType,
    symbol = irBuiltIns.anyClass.constructors.single(),
  )
}
