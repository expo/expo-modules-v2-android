package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.TypeDescriptorNames
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.createExtensionReceiver
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
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
import org.jetbrains.kotlin.ir.symbols.impl.IrFieldSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
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

    val reference = IrSyntheticClassReferenceImpl(
      type = irBuiltIns.kClassClass.starProjectedType,
      symbol = symbol,
      classType = type,
    )

    return IrSyntheticCallImpl(
      type = symbols.classes.javaLangClass.typeWith(type),
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

  /** `AnyType(descriptor, useBuffer)`, as `ModuleBuilder` wants a value described. */
  internal fun anyTypeOf(plan: ValuePlan): IrExpression =
    constructorCall(
      constructor = symbols.constructors.anyType,
      type = symbols.classes.anyType.owner.defaultType,
      arguments = listOf(descriptorFor(plan.type), boolean(plan.buffered)),
    )

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

    containerDescriptor(type, isNullable, fqName)?.let { return it }

    return simpleDescriptor(type, isNullable)
  }

  private fun containerDescriptor(
    type: IrSimpleType,
    isNullable: Boolean,
    fqName: String,
  ): IrExpression? {
    sharedRefDescriptor(type)?.let { return it }

    val arguments = type.arguments
    val containerClass: IrClassSymbol = when (fqName) {
      "kotlin.collections.List", "kotlin.collections.MutableList" -> irBuiltIns.listClass
      "kotlin.collections.Set", "kotlin.collections.MutableSet" -> irBuiltIns.setClass
      "kotlin.collections.Map", "kotlin.collections.MutableMap" -> irBuiltIns.mapClass
      "kotlin.Array" -> return arrayDescriptor(type, isNullable)
      else -> return null
    }

    val parameters = arguments.map { argument ->
      val projected = (argument as? IrTypeProjection)?.type ?: unsupported(type)
      boxedDescriptorFor(projected)
    }

    return parametrized(javaClass(containerClass.owner.defaultType), isNullable, parameters)
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
    )
  }

  private fun parametrized(
    javaClassExpression: IrExpression,
    isNullable: Boolean,
    parameters: List<IrExpression>,
  ): IrExpression {
    val constructor = symbols.constructors.parametrizedDescriptor

    return constructorCall(
      constructor = constructor,
      type = constructor.owner.returnType,
      arguments = listOf(
        javaClassExpression,
        boolean(isNullable),
        // `params` is a real array parameter, not a vararg.
        arrayOf(symbols.classes.typeDescriptorObjectLike.owner.defaultType, parameters),
      ),
    )
  }

  private fun simpleDescriptor(type: IrSimpleType, isNullable: Boolean): IrExpression {
    val constructor = symbols.constructors.simpleDescriptor
    val erased = type.classOrNull?.owner?.defaultType ?: unsupported(type)

    return constructorCall(
      constructor = constructor,
      type = constructor.owner.returnType,
      arguments = listOf(javaClass(erased), boolean(isNullable)),
    )
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
