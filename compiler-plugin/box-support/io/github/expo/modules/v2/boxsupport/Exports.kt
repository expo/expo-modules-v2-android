package io.github.expo.modules.v2.boxsupport

import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.modules.ModuleBuilder
import io.github.expo.modules.v2.sharedobjects.SharedObjectRegistry
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.TypeDescriptor
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/*
 * Assertions for the compiler plugin's box tests, on their compile and run classpath beside :api.
 *
 * A box test cannot reach JavaScript, so what a generated `define$ExpoModulesV2` declares is checked by
 * running it into a fresh [ModuleBuilder] and comparing what it recorded with what the test declares:
 *
 *     assertExports(Enums) {
 *         function("echoStyle") {
 *             args(buffered<Style>())
 *             returns(buffered<Style>())
 *             trampoline()
 *         }
 *     }
 *
 * The comparison is exhaustive: an export the test does not declare fails it as much as a missing or
 * different one. This source set is compiled as a friend of :api, so it reads the builder's internal
 * definitions directly.
 */

/** Asserts that [module]'s generated `define$ExpoModulesV2` declares exactly what [expected] does. */
fun assertExports(module: Module, expected: ExportsSpec.() -> Unit) {
  val builder = ModuleBuilder().also { module.`define$ExpoModulesV2`(it) }
  check(module.javaClass.simpleName, builder, ExportsSpec().apply(expected))
}

/** The same for a shared-object class, whose `define$ExpoModulesV2` is static. */
fun assertExports(sharedObject: KClass<out SharedObject>, expected: ExportsSpec.() -> Unit) {
  val builder = ModuleBuilder()
  sharedObject.java.getDeclaredMethod(DEFINE, ModuleBuilder::class.java)
    .apply { isAccessible = true }
    .invoke(null, builder)
  check(sharedObject.java.simpleName, builder, ExportsSpec().apply(expected))
}

/** A type crossing in its own JNI slot. */
inline fun <reified T> slot(): ExpectedType = ExpectedType(typeOf<T>(), buffered = false)

/** A type riding the call's payload buffer. */
inline fun <reified T> buffered(): ExpectedType = ExpectedType(typeOf<T>(), buffered = true)

class ExpectedType(val type: KType, val buffered: Boolean) {
  override fun toString(): String = transport(buffered, render(type))
}

class ExportsSpec {
  internal val functions = mutableMapOf<String, FunctionSpec>()
  internal val properties = mutableMapOf<String, PropertySpec>()
  internal val sharedClasses = mutableMapOf<String, SharedClassSpec>()
  internal val events = mutableMapOf<String, ExpectedType>()

  /** An exported function. With no `returns`, it returns `Unit`. */
  fun function(name: String, spec: FunctionSpec.() -> Unit = {}) {
    functions[name] = FunctionSpec(name).apply(spec)
  }

  fun property(name: String, type: ExpectedType, spec: PropertySpec.() -> Unit = {}) {
    properties[name] = PropertySpec(name, type).apply(spec)
  }

  /** A shared-object class the module exposes, and the arguments of its JavaScript constructor. */
  fun sharedClass(name: String, type: KClass<out SharedObject>, vararg args: ExpectedType) {
    sharedClasses[name] = SharedClassSpec(type, args.toList())
  }

  fun event(name: String, payload: ExpectedType) {
    events[name] = payload
  }
}

class FunctionSpec internal constructor(private val name: String) {
  internal var args: List<ExpectedType> = emptyList()
  internal var returns: ExpectedType = slot<Unit>()
  internal var method: String = name
  internal var isAsync: Boolean = false

  fun args(vararg types: ExpectedType) {
    args = types.toList()
  }

  fun returns(type: ExpectedType) {
    returns = type
  }

  /** JavaScript calls the generated `<name>__trampoline$ExpoModulesV2` rather than the function. */
  fun trampoline() {
    method = name + TRAMPOLINE
  }

  /** JavaScript calls this JVM method, such as the original name of an export renamed with `@JS`. */
  fun method(name: String) {
    method = name
  }

  /** Resolved through a promise: a `suspend` function, or one returning `Promise<T>`. */
  fun async() {
    isAsync = true
  }
}

class PropertySpec internal constructor(private val name: String, internal val type: ExpectedType) {
  internal var isConstant: Boolean = false
  internal var accessorsOf: String = name
  internal var setterType: ExpectedType? = null

  fun constant() {
    isConstant = true
  }

  /** The accessors JavaScript calls are the generated `__trampoline$ExpoModulesV2` ones. */
  fun trampoline() {
    accessorsOf = name + TRAMPOLINE
  }

  /** JavaScript can write it, as [setterType] - the property's own type unless that differs. */
  fun mutable(setterType: ExpectedType = type) {
    this.setterType = setterType
  }
}

class SharedClassSpec internal constructor(
  internal val type: KClass<out SharedObject>,
  internal val args: List<ExpectedType>,
)

private fun check(owner: String, actual: ModuleBuilder, expected: ExportsSpec) {
  val problems = mutableListOf<String>()

  compare("function", actual.functions.associateBy { it.jsName }, expected.functions, problems) { fn, spec ->
    listOfNotNull(
      typesDiffer("arguments", fn.argTypes.toList(), spec.args),
      typeDiffers("return type", fn.returnType, spec.returns),
      differs("method", fn.methodName.short(), spec.method.short()),
      differs("async", fn.isAsync, spec.isAsync),
    )
  }

  compare("property", actual.properties.associateBy { it.jsName }, expected.properties, problems) { prop, spec ->
    val (getter, setter) = accessorNames(spec.accessorsOf, mutable = spec.setterType != null)
    val setterType = prop.setterType
    val expectedSetterType = spec.setterType
    listOfNotNull(
      typeDiffers("type", prop.getterType, spec.type),
      differs("constant", prop.isConstant, spec.isConstant),
      differs("getter", prop.getterName.short(), getter.short()),
      differs("setter", prop.setterName?.short(), setter?.short()),
      if (setterType != null &&
        expectedSetterType != null
      ) {
        typeDiffers("setter type", setterType, expectedSetterType)
      } else {
        null
      },
    )
  }

  compare(
    "shared class",
    actual.sharedClasses.associateBy {
      it.jsName
    },
    expected.sharedClasses,
    problems,
  ) { cls, spec ->
    val expectedId = SharedObjectRegistry.classIdFor(spec.type.java).value
    listOfNotNull(
      if (cls.classId == expectedId) null else "registers another class than ${spec.type.java.simpleName}",
      typesDiffer("constructor arguments", cls.argTypes.toList(), spec.args),
      differs("constructor", cls.trampolineName.short(), CONSTRUCTOR.short()),
    )
  }

  compare("event", actual.events.associateBy { it.jsName }, expected.events, problems) { event, payload ->
    listOfNotNull(typeDiffers("payload", event.payloadType, payload))
  }

  if (problems.isNotEmpty()) {
    throw AssertionError("$owner declares other exports than expected:\n" + problems.joinToString("\n") { "  $it" })
  }
}

private fun <A, E> compare(
  kind: String,
  actual: Map<String, A>,
  expected: Map<String, E>,
  problems: MutableList<String>,
  differences: (actual: A, expected: E) -> List<String>,
) {
  for (name in expected.keys - actual.keys) problems += "missing $kind $name"
  for (name in actual.keys - expected.keys) problems += "unexpected $kind $name"
  for (name in expected.keys intersect actual.keys) {
    differences(actual.getValue(name), expected.getValue(name)).forEach { problems += "$kind $name: $it" }
  }
}

private fun <T> differs(what: String, actual: T, expected: T): String? =
  if (actual == expected) null else "$what is $actual, expected $expected"

private fun typeDiffers(what: String, actual: AnyType, expected: ExpectedType): String? =
  if (matches(actual, expected)) null else "$what is ${render(actual)}, expected $expected"

private fun typesDiffer(what: String, actual: List<AnyType>, expected: List<ExpectedType>): String? =
  if (actual.size == expected.size && actual.zip(expected).all { (a, e) -> matches(a, e) }) {
    null
  } else {
    "$what are ${actual.joinToString(prefix = "(", postfix = ")") { render(it) }}, " +
      "expected ${expected.joinToString(prefix = "(", postfix = ")")}"
  }

private fun render(type: AnyType): String = transport(type.useBuffer, render(type.descriptor))

private fun transport(buffered: Boolean, type: String) = if (buffered) "buffered<$type>" else "slot<$type>"

/** [type] as a test writes it: `List<Int>?`. `KType.toString()` would need kotlin-reflect. */
private fun render(type: KType): String {
  val name = (type.classifier as? KClass<*>)?.simpleName ?: type.classifier.toString()
  val arguments = type.arguments.map { it.type?.let(::render) ?: "*" }
  return name + (if (arguments.isEmpty()) "" else arguments.joinToString(prefix = "<", postfix = ">")) +
    (if (type.isMarkedNullable) "?" else "")
}

/** [descriptor] in the same notation, so a failure reads `slot<Int>` against `slot<Int?>`. */
private fun render(descriptor: TypeDescriptor): String = when (descriptor) {
  is TypeDescriptor.Primitive -> PRIMITIVES.entries.first { it.value == descriptor }.key.simpleName!!
  is TypeDescriptor.PrimitiveArray ->
    PRIMITIVE_ARRAYS.entries.first { it.value.isInstance(descriptor) }.key.simpleName!! +
      (if (descriptor.isNullable) "?" else "")
  is TypeDescriptor.Simple -> descriptor.javaClass.kotlin.simpleName + (if (descriptor.isNullable) "?" else "")
  is TypeDescriptor.Parametrized ->
    descriptor.javaClass.kotlin.simpleName + descriptor.params.joinToString(prefix = "<", postfix = ">") {
      render(it)
    } +
      (if (descriptor.isNullable) "?" else "")
}

private fun matches(actual: AnyType, expected: ExpectedType): Boolean =
  actual.useBuffer == expected.buffered && matches(actual.descriptor, expected.type, topLevel = true)

/**
 * Whether [descriptor] is how [type] crosses. A non-null Kotlin primitive at the top level is the
 * unboxed primitive descriptor; inside a generic type it is its boxed class. A primitive array is its
 * own descriptor, and anything else is its class - with the type's arguments, when it has any.
 */
private fun matches(descriptor: TypeDescriptor, type: KType, topLevel: Boolean): Boolean {
  val kClass = type.classifier as? KClass<*> ?: return false
  val nullable = type.isMarkedNullable

  val primitive = PRIMITIVES[kClass]
  if (primitive != null && topLevel && !nullable) {
    return descriptor == primitive
  }
  val primitiveArray = PRIMITIVE_ARRAYS[kClass]
  if (primitiveArray != null) {
    return primitiveArray.isInstance(descriptor) && (descriptor as TypeDescriptor.PrimitiveArray).isNullable == nullable
  }
  return when (descriptor) {
    is TypeDescriptor.Simple ->
      type.arguments.isEmpty() && descriptor.javaClass == kClass.javaObjectType && descriptor.isNullable == nullable
    is TypeDescriptor.Parametrized ->
      descriptor.javaClass == kClass.java &&
        descriptor.isNullable == nullable &&
        descriptor.params.size == type.arguments.size &&
        descriptor.params.zip(type.arguments).all { (param, argument) ->
          val argumentType = argument.type
          argumentType != null && matches(param, argumentType, topLevel = false)
        }
    else -> false
  }
}

/** The JVM accessors `ModuleBuilder.property` derives from a Kotlin property name. */
private fun accessorNames(propertyName: String, mutable: Boolean): Pair<String, String?> {
  val isPrefixed = propertyName.length > 2 && propertyName.startsWith("is") && !propertyName[2].isLowerCase()
  val suffix = if (isPrefixed) propertyName.substring(2) else propertyName.replaceFirstChar { it.uppercaseChar() }
  val getter = if (isPrefixed) propertyName else "get$suffix"
  return getter to if (mutable) "set$suffix" else null
}

/** Drops the suffix every generated name carries, to keep failure messages short. */
private fun String.short(): String = replace(GENERATED_SUFFIX, "")

private val PRIMITIVES: Map<KClass<*>, TypeDescriptor.Primitive> = mapOf(
  Int::class to TypeDescriptor.Int,
  Long::class to TypeDescriptor.Long,
  Float::class to TypeDescriptor.Float,
  Double::class to TypeDescriptor.Double,
  Boolean::class to TypeDescriptor.Bool,
)

private val PRIMITIVE_ARRAYS: Map<KClass<*>, Class<out TypeDescriptor.PrimitiveArray>> = mapOf(
  IntArray::class to TypeDescriptor.IntArray::class.java,
  LongArray::class to TypeDescriptor.LongArray::class.java,
  FloatArray::class to TypeDescriptor.FloatArray::class.java,
  DoubleArray::class to TypeDescriptor.DoubleArray::class.java,
  ByteArray::class to TypeDescriptor.ByteArray::class.java,
  BooleanArray::class to TypeDescriptor.BooleanArray::class.java,
)

private const val GENERATED_SUFFIX = "\$ExpoModulesV2"
private const val DEFINE = "define$GENERATED_SUFFIX"
private const val TRAMPOLINE = "__trampoline$GENERATED_SUFFIX"
private const val CONSTRUCTOR = "__construct$GENERATED_SUFFIX"
