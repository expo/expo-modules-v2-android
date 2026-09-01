package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.RecordKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.Name

/**
 * Fills every body the FIR extension declared for an `@Record` class.
 */
class RecordCodecIrTransformer(
  private val context: IrPluginContext,
  private val symbols: SymbolFinder,
  private val poet: TypeDescriptorPoet,
) : IrVisitorVoid() {
  private class Field(
    val name: String,
    val type: IrType,
    val property: IrProperty,
    val parameter: IrValueParameter,
  ) {
    val isOptional: Boolean get() = parameter.defaultValue != null
  }

  private val irBuiltIns = context.irBuiltIns

  private val descriptors = DescriptorFields(
    context, symbols, poet, RecordKey, isStatic = false,
  )

  override fun visitElement(element: IrElement) {
    element.acceptChildrenVoid(this)
  }

  override fun visitClass(declaration: IrClass) {
    declaration.acceptChildrenVoid(this)

    if (!declaration.hasAnnotation(Identifiers.Classes.RecordAnnotation)) {
      return
    }

    val codecClass = declaration.declarations
      .filterIsInstance<IrClass>()
      .firstOrNull { it.name == Identifiers.Names.CODEC_CLASS }
      ?: return

    val companion = declaration.declarations
      .filterIsInstance<IrClass>()
      .firstOrNull { it.isCompanion }
      ?: return

    generate(declaration, codecClass, companion)
  }

  private fun generate(record: IrClass, codecClass: IrClass, companion: IrClass) {
    val fields = recordFields(record)

    fillRecordClass(record, codecClass)
    fillSchema(record, codecClass, fields)
    fillEncode(codecClass, fields)
    fillDecode(record, codecClass, fields)
    fillCodecConstructor(codecClass)
    fillCompanion(codecClass, companion)
  }

  private fun recordFields(record: IrClass): List<Field> {
    val constructor = record.primaryConstructor ?: return emptyList()
    val properties = record.properties.associateBy { it.name }
    return constructor.parameters
      .filter { it.kind == IrParameterKind.Regular }
      .mapNotNull { parameter ->
        val property = properties[parameter.name] ?: return@mapNotNull null
        Field(parameter.name.asString(), parameter.type, property, parameter)
      }
  }

  private fun IrSimpleFunction.dispatchReceiver(): IrValueParameter =
    parameters.single { it.kind == IrParameterKind.DispatchReceiver }

  private fun fillRecordClass(record: IrClass, codecClass: IrClass) {
    val property = codecClass.property(Identifiers.Names.RECORD_CLASS)
      ?: return

    property.backingField?.let { field ->
      field.initializer = context.irFactory.createSyntheticExpressionBody(
        poet.javaClass(record.defaultType),
      )
    }

    property.generateDefaultPropertyGetter(context.irFactory)
  }

  private fun fillSchema(
    record: IrClass,
    codecClass: IrClass,
    fields: List<Field>,
  ) {
    val property = codecClass.property(Identifiers.Names.SCHEMA) ?: return
    val backingField = property.backingField ?: return

    val annotation = record.getAnnotation(Identifiers.FqNames.RECORD_ANNOTATION)
    val schemaName = annotation
      .stringArgument(Identifiers.Names.ARG_NAME)
      ?.takeIf { it.isNotEmpty() }
      ?: record.name.asString()
    val bufferSafe = annotation.booleanArgument(Identifiers.Names.ARG_BUFFER_SAFE) ?: true

    val codecThis = codecClass.thisReceiver
      ?: error("${codecClass.name} has no dispatch receiver")

    val fieldExpressions = fields.map { field ->
      poet.constructorCall(
        constructor = symbols.constructors.recordField,
        type = symbols.classes.recordField.owner.defaultType,
        arguments = listOf(
          poet.string(field.name),
          descriptors.read(codecClass, field.type, codecThis),
          poet.boolean(field.isOptional),
        ),
      )
    }

    val schema = poet.constructorCall(
      constructor = symbols.constructors.recordSchema,
      type = symbols.classes.recordSchema.owner.defaultType,
      arguments = listOf(
        poet.string(schemaName),
        poet.boolean(bufferSafe),
        IrSyntheticVarargImpl(
          type = irBuiltIns.arrayClass.typeWith(symbols.classes.recordField.owner.defaultType),
          varargElementType = symbols.classes.recordField.owner.defaultType,
          elements = fieldExpressions,
        ),
      ),
    )

    backingField.initializer = context.irFactory.createSyntheticExpressionBody(schema)
    property.generateDefaultPropertyGetter(context.irFactory)
  }

  private fun fillEncode(
    codecClass: IrClass,
    fields: List<Field>,
  ) {
    val encode = codecClass.function(Identifiers.Names.ENCODE) ?: return
    val codecThis = encode.dispatchReceiver()
    val regular = encode.parameters.filter { it.kind == IrParameterKind.Regular }
    val valueParameter = regular[0]
    val writerParameter = regular[1]

    val body = context.irFactory.createSyntheticBlockBody()
    for (field in fields) {
      val getter = field.property.getter ?: error("${field.name} has no getter")
      // TODO(@lukmccall): can we use backing field instead of getter?
      val read = IrSyntheticCallImpl(getter.returnType, getter.symbol).apply {
        arguments[0] = IrSyntheticGetValueImpl(valueParameter.type, valueParameter.symbol)
      }

      val primitive = primitiveName(field.type)
      val call = if (primitive != null) {
        callOn(
          function = symbols.functions.writePrimitive(
            field.type.classOrNull!!,
            field.type.isMarkedNullable()
          ),
          receiver = writerParameter.get(),
          arguments = listOf(read),
          returnType = irBuiltIns.unitType,
        )
      } else {
        callOn(
          function = symbols.functions.writeWithDescriptor,
          receiver = writerParameter.get(),
          arguments = listOf(read, descriptors.read(codecClass, field.type, codecThis)),
          returnType = irBuiltIns.unitType,
        )
      }
      body.statements += call
    }
    encode.body = body
  }

  private fun fillDecode(
    record: IrClass,
    codecClass: IrClass,
    fields: List<Field>,
  ) {
    val decode = codecClass.function(Identifiers.Names.DECODE) ?: return
    val codecThis = decode.dispatchReceiver()
    val readerParameter = decode.parameters.single { it.kind == IrParameterKind.Regular }
    val constructor = record.primaryConstructor ?: return

    fun Field.toIrExpression(): IrExpression {
      val primitive = primitiveName(type)
      return if (primitive != null) {
        val suffix = if (type.isMarkedNullable()) {
          "OrNull"
        } else {
          ""
        }
        callOn(
          symbols.functions.reader("read$primitive$suffix"),
          receiver = readerParameter.get(),
          arguments = emptyList(),
          returnType = type,
        )
      } else {
        callOn(
          symbols.functions.read,
          receiver = readerParameter.get(),
          arguments = listOf(descriptors.read(codecClass, type, codecThis)),
          returnType = type,
          typeArguments = listOf(type),
        )
      }
    }

    val hasDefaults = fields.any { it.isOptional }
    if (!hasDefaults) {
      decode.body = context.irFactory.createSyntheticBlockBody().apply {
        statements += IrSyntheticReturnImpl(
          type = record.defaultType,
          returnTargetSymbol = decode.symbol,
          value = poet.constructorCall(
            constructor = constructor.symbol,
            type = record.defaultType,
            arguments = fields.map { it.toIrExpression() },
          ),
        )
      }
      decode.patchDeclarationParents(codecClass)
      return
    }

    val locals = mutableListOf<IrVariable>()
    val remap = mutableMapOf<IrValueSymbol, IrValueSymbol>()
    val statements = mutableListOf<IrStatement>()

    for (field in fields) {
      val fieldExpression = field.toIrExpression()

      val initializer = if (field.isOptional) {
        val default = requireNotNull(field.parameter.defaultValue).expression
          .deepCopyWithSymbols(decode)
          .remapValues(remap)

        IrSyntheticWhenImpl(field.type)
          .apply {
            val irBranches = listOf(
              IrSyntheticBranchImpl(
                condition = callOn(
                  symbols.functions.readIsPresent,
                  receiver = readerParameter.get(),
                  arguments = emptyList(),
                  returnType = irBuiltIns.booleanType,
                ),
                result = fieldExpression,
              ),
              IrSyntheticElseBranchImpl(
                condition = IrSyntheticConstImpl.boolean(irBuiltIns.booleanType, true),
                result = default,
              )
            )
            branches += irBranches
          }
      } else {
        fieldExpression
      }

      val variable = buildSyntheticVariable(
        parent = decode,
        origin = IrDeclarationOrigin.DEFINED,
        name = field.parameter.name,
        type = field.type,
      ).apply {
        this.initializer = initializer
      }
      locals += variable
      remap[field.parameter.symbol] = variable.symbol
      statements += variable
    }

    statements += IrSyntheticReturnImpl(
      type = record.defaultType,
      returnTargetSymbol = decode.symbol,
      value = poet.constructorCall(
        constructor = constructor.symbol,
        type = record.defaultType,
        arguments = locals.map { IrSyntheticGetValueImpl(it.type, it.symbol) },
      ),
    )

    decode.body = context.irFactory.createSyntheticBlockBody().apply {
      this.statements += statements
    }
    decode.patchDeclarationParents(codecClass)
  }

  /**
   * `<init>() { super(); <instance initializers>; RecordRegistry.register(this) }`
   */
  private fun fillCodecConstructor(codecClass: IrClass) {
    val constructor = codecClass.constructors.singleOrNull() ?: return
    val codecThis = codecClass.thisReceiver ?: return

    val body = context.irFactory.createSyntheticBlockBody()
    // TODO(@lukmccall): should we support inheritance
    body.statements += delegatingAnyConstructorCall()
    body.statements += IrSyntheticInstanceInitializerCallImpl(
      classSymbol = codecClass.symbol,
      type = irBuiltIns.unitType,
    )
    body.statements += IrSyntheticCallImpl(
      type = irBuiltIns.unitType,
      symbol = symbols.functions.register,
    ).apply {
      val registerFunction = symbols.functions.register.owner
      registerFunction
        .parameters
        .forEach { parameter ->
          when (parameter.kind) {
            IrParameterKind.DispatchReceiver ->
              arguments[parameter.indexInParameters] = objectValue(symbols.classes.recordRegistry)

            IrParameterKind.Regular ->
              arguments[parameter.indexInParameters] = IrSyntheticGetValueImpl(codecThis.type, codecThis.symbol)

            else -> Unit
          }
        }
    }
    constructor.body = body
  }

  /**
   * `val recordCodec$ExpoModulesV2 = RecordCodec$ExpoModulesV2()`.
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

    val property = companion.property(Identifiers.Names.CODEC_PROPERTY) ?: return
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

  private fun IrClass.property(name: Name): IrProperty? =
    declarations.filterIsInstance<IrProperty>().firstOrNull { it.name == name }

  private fun IrClass.function(name: Name): IrSimpleFunction? =
    declarations.filterIsInstance<IrSimpleFunction>().firstOrNull { it.name == name }

  private fun delegatingAnyConstructorCall() = IrSyntheticDelegatingConstructorCallImpl(
    type = irBuiltIns.unitType,
    symbol = irBuiltIns.anyClass.constructors.single(),
  )

  private fun objectValue(symbol: org.jetbrains.kotlin.ir.symbols.IrClassSymbol): IrExpression =
    IrSyntheticGetObjectValueImpl(symbol.owner.defaultType, symbol)

  /** `Int`/`Long`/`Float`/`Double`/`Boolean` (nullable or not), or null for everything else. */
  private fun primitiveName(type: IrType): String? {
    val fqName = type.classOrNull?.owner?.kotlinFqName?.asString() ?: return null
    return when (fqName) {
      "kotlin.Int" -> "Int"
      "kotlin.Long" -> "Long"
      "kotlin.Float" -> "Float"
      "kotlin.Double" -> "Double"
      "kotlin.Boolean" -> "Boolean"
      else -> null
    }
  }

  private fun IrConstructorCall?.stringArgument(name: Name): String? =
    this?.argumentByName(name)?.let { (it as? IrConst)?.value as? String }

  private fun IrConstructorCall?.booleanArgument(name: Name): Boolean? =
    this?.argumentByName(name)?.let { (it as? IrConst)?.value as? Boolean }

  private fun IrConstructorCall.argumentByName(name: Name): IrExpression? {
    val parameter = symbol.owner.parameters.firstOrNull {
      it.kind == IrParameterKind.Regular && it.name == name
    } ?: return null
    return arguments[parameter.indexInParameters]
  }
}

private fun IrExpression.remapValues(remap: Map<IrValueSymbol, IrValueSymbol>): IrExpression {
  if (remap.isEmpty()) {
    return this
  }

  return transform(
    object : IrElementTransformerVoid() {
      override fun visitGetValue(expression: IrGetValue): IrExpression {
        val replacement = remap[expression.symbol] ?: return expression
        return IrSyntheticGetValueImpl(
          type = expression.type,
          symbol = replacement,
        )
      }
    },
    null,
  )
}
