package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.TypeDescriptorNames
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.createExtensionReceiver
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrFieldSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isSubclassOf
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class TypeDescriptorPoet(
  context: IrPluginContext,
  private val symbols: SymbolFinder,
  moduleFragment: IrModuleFragment,
) {
  private val irBuiltIns = context.irBuiltIns

  /**
   * Synthetic symbol for the `kotlin.jvm.<get-java>` extension property on `KClass<*>`. The JVM
   * backend matches it by (package, receiver, name) and codegens a bare `LDC LType;` instead of a
   * `Reflection.getOrCreateKotlinClass` wrap.
   */
  private val kClassJavaGetter: IrSimpleFunctionSymbol = run {
    val kotlinJvmPackage = createEmptyExternalPackageFragment(
      moduleFragment.descriptor,
      FqName("kotlin.jvm"),
    )
    val property = IrFactoryImpl.buildSyntheticProperty {
      name = Name.identifier("java")
    }.apply {
      parent = kotlinJvmPackage
      addSyntheticGetter().apply {
        parameters += createExtensionReceiver(irBuiltIns.kClassClass.starProjectedType)
        returnType = symbols.classes.javaLangClass.owner.defaultType
      }
    }

    requireNotNull(property.getter).symbol
  }

  fun string(value: String): IrExpression =
    IrSyntheticConstImpl.string(irBuiltIns.stringType, value)

  fun boolean(value: Boolean): IrExpression =
    IrSyntheticConstImpl.boolean(irBuiltIns.booleanType, value)

  /** `T::class.java`, for any [type] - including `Array<String>`, whose literal is `[Ljava/lang/String;`. */
  fun javaClass(type: IrType): IrExpression {
    val symbol = type.classOrNull
      ?: error("Cannot take a class literal of $type")
    // A class's default type names its own type parameters, which are out of scope here, and
    // Kotlin 2.4.20's IR validation rejects that. Such a type is star-projected, like
    // `List::class.java` in source (`Class<List<*>>`). A concrete type such as `Array<String>` stays
    // as it is, because its arguments decide the JVM class.
    val literalType = if (type.mentionsTypeParameter()) symbol.starProjectedType else type

    val reference = IrSyntheticClassReferenceImpl(
      type = irBuiltIns.kClassClass.starProjectedType,
      symbol = symbol,
      classType = literalType,
    )

    return IrSyntheticCallImpl(
      type = symbols.classes.javaLangClass.typeWith(literalType),
      symbol = kClassJavaGetter,
    ).apply {
      arguments[0] = reference
    }
  }

  /** `arrayOf<T>(...)`. */
  fun arrayOf(elementType: IrType, elements: List<IrExpression>): IrExpression {
    val arrayType = irBuiltIns.arrayClass.typeWith(elementType)
    return IrSyntheticCallImpl(
      type = arrayType,
      symbol = irBuiltIns.arrayOf,
      typeArgumentsCount = 1
    ).apply {
      typeArguments[0] = elementType
      arguments[0] = IrSyntheticVarargImpl(arrayType, elementType, elements)
    }
  }

  /** Fills a call's arguments from [values], skipping receivers, in parameter order. */
  fun IrConstructorCallImpl.fillRegularArguments(
    constructor: IrConstructorSymbol,
    values: List<IrExpression>,
  ) {
    var next = 0
    constructor.owner.parameters.forEach { parameter ->
      if (parameter.kind == IrParameterKind.Regular) {
        arguments[parameter.indexInParameters] = values[next++]
      }
    }
  }

  fun constructorCall(
    constructor: IrConstructorSymbol,
    type: IrType,
    arguments: List<IrExpression>,
  ): IrExpression = IrSyntheticConstructorCallImpl(
    type = type,
    symbol = constructor,
  ).apply { fillRegularArguments(constructor, arguments) }

  /** `TypeDescriptor` for a record field of [type], as it appears in the constructor. */
  fun descriptorFor(type: IrType): IrExpression {
    val simpleType = type as? IrSimpleType ?: unsupported(type)
    val isNullable = simpleType.isMarkedNullable()
    val name = simpleType.classOrNull?.owner?.kotlinFqName?.asString() ?: unsupported(type)

    if (!isNullable) {
      TypeDescriptorNames.primitiveSingleton(name)?.let { classId ->
        val objectSymbol = symbols.classes.descriptor(classId)
        return IrSyntheticGetObjectValueImpl(objectSymbol.owner.defaultType, objectSymbol)
      }
    }

    return descriptorForBoxed(simpleType, isNullable, name)
  }

  fun boxedDescriptorFor(type: IrType): IrExpression {
    val simpleType = type as? IrSimpleType ?: unsupported(type)
    val name = simpleType.classOrNull?.owner?.kotlinFqName?.asString() ?: unsupported(type)
    return descriptorForBoxed(simpleType, simpleType.isMarkedNullable(), name)
  }

  private fun descriptorForBoxed(
    type: IrSimpleType,
    isNullable: Boolean,
    fqName: String,
  ): IrExpression {
    TypeDescriptorNames.commonField(fqName, isNullable)?.let { return commonDescriptor(it) }

    TypeDescriptorNames.primitiveArray(fqName)?.let { classId ->
      val constructor = symbols.constructors.descriptor(classId)
      return constructorCall(
        constructor,
        constructor.owner.returnType,
        listOf(boolean(isNullable)),
      )
    }

    functionDescriptor(type, isNullable, fqName)?.let { return it }

    containerDescriptor(type, isNullable, fqName)?.let { return it }

    return simpleDescriptor(type, isNullable)
  }

  /**
   * `TypeDescriptor.Function(params, returns, isSuspend, isNullable)` for a lambda type. Its
   * arguments are the parameters followed by the return type, all described as boxed values, which
   * is how a lambda sees them on the JVM.
   */
  private fun functionDescriptor(
    type: IrSimpleType,
    isNullable: Boolean,
    fqName: String,
  ): IrExpression? {
    if (!FunctionTypes.isFunction(fqName)) {
      return null
    }

    val arguments = type.arguments.map { argument ->
      (argument as? IrTypeProjection)?.type ?: unsupported(type)
    }
    val constructor = symbols.constructors.functionDescriptor

    return constructorCall(
      constructor = constructor,
      type = constructor.owner.returnType,
      arguments = listOf(
        arrayOf(
          symbols.classes.typeDescriptorObjectLike.owner.defaultType,
          arguments.dropLast(1).map(::boxedDescriptorFor),
        ),
        boxedDescriptorFor(arguments.last()),
        boolean(FunctionTypes.isSuspendFunction(fqName)),
        boolean(isNullable),
      ),
    )
  }

  private fun containerDescriptor(
    type: IrSimpleType,
    isNullable: Boolean,
    fqName: String,
  ): IrExpression? {
    sharedRefDescriptor(type)?.let { return it }

    val arguments = type.arguments
    val (containerClass: IrClassSymbol, factory: String) = when (fqName) {
      "kotlin.collections.List", "kotlin.collections.MutableList" ->
        irBuiltIns.listClass to "ListConverterFactory"
      "kotlin.collections.Set", "kotlin.collections.MutableSet" ->
        irBuiltIns.setClass to "SetConverterFactory"
      "kotlin.collections.Collection", "kotlin.collections.MutableCollection" ->
        irBuiltIns.collectionClass to "CollectionConverterFactory"
      "kotlin.collections.Map", "kotlin.collections.MutableMap" ->
        irBuiltIns.mapClass to "MapConverterFactory"
      "kotlin.Pair" -> (type.classOrNull ?: unsupported(type)) to "PairConverterFactory"
      "kotlin.Triple" -> (type.classOrNull ?: unsupported(type)) to "TripleConverterFactory"
      // Only as what a lambda returns, where the runtime reads the type it resolves with.
      Identifiers.FqNames.PROMISE.asString() ->
        (type.classOrNull ?: unsupported(type)) to "PromiseResultConverterFactory"
      "kotlin.Array" -> return arrayDescriptor(type, isNullable)
      else -> return null
    }

    val parameters = arguments.map { argument ->
      val projected = (argument as? IrTypeProjection)?.type ?: unsupported(type)
      boxedDescriptorFor(projected)
    }

    return parametrized(javaClass(containerClass.owner.defaultType), isNullable, parameters, factory)
  }

  private fun sharedRefDescriptor(type: IrSimpleType): IrExpression? {
    val owner = type.classOrNull?.owner ?: return null
    if (!owner.isSubclassOf(symbols.classes.sharedRef.owner)) {
      return null
    }

    val argument = type.arguments.singleOrNull() as? IrTypeProjection ?: return null
    return parametrized(
      javaClass(owner.defaultType),
      type.isMarkedNullable(),
      listOf(boxedDescriptorFor(argument.type)),
      "SharedRefConverterFactory",
    )
  }

  /**
   * `Array<E>` - the java class is the **array** class (`LDC [Ljava/lang/String;`), not `Array`,
   * because that is what `ArrayConverter` reflects on to build a result array.
   */
  private fun arrayDescriptor(type: IrSimpleType, isNullable: Boolean): IrExpression {
    val element = (type.arguments.single() as? IrTypeProjection)?.type ?: unsupported(type)

    return parametrized(
      javaClass(type),
      isNullable,
      listOf(boxedDescriptorFor(element)),
      "ArrayConverterFactory",
    )
  }

  /**
   * `TypeDescriptor.Parametrized(javaClass, isNullable, params, <factory>)`. Naming the factory is
   * what keeps the other container converters unreachable from this app's code. A runtime without
   * the factory gets the three-argument form, and its registry picks.
   */
  private fun parametrized(
    javaClassExpression: IrExpression,
    isNullable: Boolean,
    parameters: List<IrExpression>,
    factoryName: String,
  ): IrExpression {
    val arguments = listOf(
      javaClassExpression,
      boolean(isNullable),
      // `params` is a real array parameter, not a vararg.
      arrayOf(symbols.classes.typeDescriptorObjectLike.owner.defaultType, parameters),
    )

    val factory = converterFactory(factoryName)
    val withFactory = symbols.constructors.parametrizedDescriptorWithFactory
    val (constructor, allArguments) = if (factory != null && withFactory != null) {
      withFactory to arguments + factory
    } else {
      symbols.constructors.parametrizedDescriptor to arguments
    }

    return constructorCall(
      constructor = constructor,
      type = constructor.owner.returnType,
      arguments = allArguments,
    )
  }

  /**
   * `TypeDescriptor.Simple` for a class, with the choice `TypeConverterRegistry` would make for it
   * at runtime made here instead, in the same order. A leaf type gets its converter directly:
   * `Simple(javaClass, isNullable, urlConverter(isNullable))`. Any other class gets the factory
   * that builds its converter on first use: `Simple(javaClass, isNullable, EnumConverterFactory)`.
   * Only a runtime without either gets the two-argument form, and its registry picks.
   */
  private fun simpleDescriptor(type: IrSimpleType, isNullable: Boolean): IrExpression {
    val owner = type.classOrNull?.owner ?: unsupported(type)
    val classAndNullability = listOf(javaClass(owner.defaultType), boolean(isNullable))

    val leafConverter = LEAF_CONVERTERS[owner.kotlinFqName.asString()]
    val (constructor, allArguments) = if (leafConverter != null) {
      val function = symbols.functions.leafConverter(leafConverter)
      val withConverter = symbols.constructors.simpleDescriptorWithConverter
      if (function != null && withConverter != null) {
        val converter = IrSyntheticCallImpl(function.owner.returnType, function).apply {
          arguments[0] = boolean(isNullable)
        }
        withConverter to classAndNullability + converter
      } else {
        symbols.constructors.simpleDescriptor to classAndNullability
      }
    } else {
      val factory = converterFactory(simpleFactoryName(owner))
      val withFactory = symbols.constructors.simpleDescriptorWithFactory
      if (factory != null && withFactory != null) {
        withFactory to classAndNullability + factory
      } else {
        symbols.constructors.simpleDescriptor to classAndNullability
      }
    }

    return constructorCall(
      constructor = constructor,
      type = constructor.owner.returnType,
      arguments = allArguments,
    )
  }

  private fun simpleFactoryName(owner: IrClass): String =
    when {
      owner.isSubclassOf(symbols.classes.sharedObject.owner) -> "SharedObjectConverterFactory"
      owner.isSubclassOf(symbols.classes.typedArray.owner) -> "TypedArrayConverterFactory"
      owner.kind == ClassKind.ENUM_CLASS -> "EnumConverterFactory"
      // The annotation, not the `Union` supertype: a union's variants inherit that, and a variant
      // that is a record converts as the record.
      owner.hasAnnotation(Identifiers.FqNames.UNION_ANNOTATION) -> "UnionConverterFactory"
      owner.isSubclassOf(symbols.classes.recordInterface.owner) -> "RecordConverterFactory"
      // The registry has nothing for it either. Saying so here keeps the registry - and with it
      // every converter - out of reach of generated code.
      else -> "UnsupportedConverterFactory"
    }

  /** `<factory>` - a read of the object, or `null` when the runtime does not have it. */
  private fun converterFactory(simpleName: String): IrExpression? {
    val symbol = symbols.classes.converterFactory(simpleName) ?: return null
    return IrSyntheticGetObjectValueImpl(symbol.owner.defaultType, symbol)
  }

  /**
   * `CommonDescriptors.<fieldName>`, read as a static field.
   */
  private fun commonDescriptor(fieldName: Name): IrExpression {
    val fieldType = symbols.classes.typeDescriptorSimple.owner.defaultType
    val field = irBuiltIns.irFactory.createSyntheticField(
      origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
      name = fieldName,
      visibility = DescriptorVisibilities.PUBLIC,
      symbol = IrFieldSymbolImpl(),
      type = fieldType,
      isFinal = true,
      isStatic = true,
      isExternal = false,
    ).also { it.parent = symbols.classes.commonDescriptors.owner }

    return IrSyntheticGetFieldImpl(
      symbol = field.symbol,
      type = fieldType,
      receiver = null
    )
  }

  private fun unsupported(type: IrType): Nothing =
    error("@Record does not support the field type $type - the frontend checker should have caught this")
}

/**
 * The leaf classes `TypeConverterRegistry` finds by exact class, and the function in
 * `LeafConverters.kt` (or next to the converter, for a type a platform may lack) that builds each
 * one's converter. The boxed primitives, `String` and `Any` are not here: `CommonDescriptors` holds
 * theirs.
 */
private val LEAF_CONVERTERS: Map<String, String> = mapOf(
  "kotlin.Unit" to "unitConverter",
  "kotlin.time.Duration" to "durationConverter",
  "kotlin.uuid.Uuid" to "kotlinUuidConverter",
  "java.util.UUID" to "uuidConverter",
  "java.io.File" to "fileConverter",
  "java.net.URL" to "urlConverter",
  "java.net.URI" to "uriConverter",
  "java.nio.file.Path" to "pathConverter",
  "android.net.Uri" to "androidUriConverter",
  "io.github.expo.modules.v2.jsi.JavaScriptValue" to "jsValueConverter",
  "io.github.expo.modules.v2.jsi.JavaScriptObject" to "jsObjectConverter",
)

private fun IrType.mentionsTypeParameter(): Boolean {
  if (classifierOrNull is IrTypeParameterSymbol) {
    return true
  }
  val arguments = (this as? IrSimpleType)?.arguments ?: return false
  return arguments.any { (it as? IrTypeProjection)?.type?.mentionsTypeParameter() == true }
}
