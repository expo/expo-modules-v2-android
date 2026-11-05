package io.github.expo.modules.v2.compiler.typegen

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.ir.ExportedMembers
import io.github.expo.modules.v2.compiler.ir.SharedClassExports
import io.github.expo.modules.v2.compiler.ir.SymbolFinder
import io.github.expo.modules.v2.compiler.ir.TransportPolicy
import io.github.expo.modules.v2.compiler.ir.ValueKind
import io.github.expo.modules.v2.compiler.ir.classReferenceArgument
import io.github.expo.modules.v2.compiler.ir.stringArgument
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrEnumConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isSubclassOf
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import io.github.expo.modules.v2.compiler.ir.ExportedEvent as BridgedEvent
import io.github.expo.modules.v2.compiler.ir.ExportedFunction as BridgedFunction
import io.github.expo.modules.v2.compiler.ir.ExportedProperty as BridgedProperty

/**
 * Describes what one compilation exports to JavaScript, for typegen.
 *
 * It asks the questions the transformers ask - [ExportedMembers] for a class's `@JS` and `@Event`
 * members, [SharedClassExports] for the constructor `new` reaches, [TransportPolicy] for what each
 * value is - so the surface it reports is the one the bridge builds. It only reads the IR, and
 * runs before the transformers change it.
 *
 * Records and enums are declared when this compilation declares them, and also when an export
 * names one from another module: the IR of a dependency's class still has its constructor and its
 * entries. An enum whose entries cross by a property is the exception there - a dependency's IR
 * does not keep the arguments of its entries - so it is left a bare reference.
 */
internal class SurfaceCollector(context: IrPluginContext, private val symbols: SymbolFinder) {
  private val policy = TransportPolicy(context, symbols)
  private val members = ExportedMembers(policy)
  private val constructors = SharedClassExports(policy)

  /** Every record described so far, by class, in the order they were reached. */
  private val records = LinkedHashMap<IrClass, ExportedRecord>()

  /** Every enum described so far, or null for one that cannot be. */
  private val enums = LinkedHashMap<IrClass, ExportedEnum?>()

  /** The shared object classes this compilation declares, so a reference can name its kind. */
  private val sharedObjectClasses = mutableSetOf<IrClass>()

  fun collect(moduleFragment: IrModuleFragment): Pair<ExportedSurface, ScanStats> {
    val start = System.nanoTime()
    val found = Declarations()
    moduleFragment.acceptChildrenVoid(found)
    sharedObjectClasses += found.sharedObjects

    val modules = found.modules.map(::module)
    val sharedObjects = found.sharedObjects.map(::sharedObject)
    found.records.forEach(::recordOf)
    found.enums.forEach(::enumOf)

    // What this compilation declares, in source order, and then what it only references.
    val surface = ExportedSurface(
      modules = modules,
      sharedObjects = sharedObjects,
      records = orderedBySource(records, found.records),
      enums = orderedBySource(enums.filterValues { it != null }.mapValues { it.value!! }, found.enums),
    )
    val stats = ScanStats(
      filesScanned = moduleFragment.files.size,
      filesParsed = found.files.size,
      durationMs = (System.nanoTime() - start) / 1_000_000,
    )
    return surface to stats
  }

  private fun <T> orderedBySource(described: Map<IrClass, T>, declared: List<IrClass>): List<T> =
    declared.mapNotNull { described[it] } + described.filterKeys { it !in declared }.values

  private fun module(moduleClass: IrClass): ExportedModule {
    val annotation = moduleClass.getAnnotation(Identifiers.FqNames.EXPO_MODULE_ANNOTATION)
    val exported = members.of(moduleClass)
    return ExportedModule(
      name = qualifiedName(moduleClass),
      jsName = annotation.stringArgument(Identifiers.Names.ARG_NAME)?.takeIf { it.isNotEmpty() }
        ?: moduleClass.name.asString(),
      functions = exported.filterIsInstance<BridgedFunction>().map(::function),
      properties = exported.filterIsInstance<BridgedProperty>().map(::property),
      events = exported.filterIsInstance<BridgedEvent>().map(::event),
      classes = classesOf(moduleClass),
      file = fileOf(moduleClass),
      accessLevel = moduleClass.visibility.spelling,
    )
  }

  /**
   * The shared classes a module exposes: those nested in it and those it lists in `classes`, the
   * way `ExpoModuleIrTransformer` finds them. A class `new` cannot reach is not exposed.
   */
  private fun classesOf(moduleClass: IrClass): List<ExportedClass> {
    val annotation = moduleClass.getAnnotation(Identifiers.FqNames.EXPO_MODULE_ANNOTATION)
    val nested = moduleClass.declarations
      .filterIsInstance<IrClass>()
      .filter { it.isSubclassOf(symbols.classes.sharedObject.owner) }
    val listed = annotation.classReferenceArgument(Identifiers.Names.ARG_CLASSES)

    return (nested + listed)
      .distinct()
      .filter { it.hasAnnotation(Identifiers.FqNames.SHARED_OBJECT_ANNOTATION) }
      .mapNotNull { sharedClass ->
        constructors.describe(sharedClass)?.let { ExportedClass(qualifiedName(sharedClass), it.jsName) }
      }
  }

  private fun sharedObject(sharedClass: IrClass): ExportedSharedObject {
    val annotation = sharedClass.getAnnotation(Identifiers.FqNames.SHARED_OBJECT_ANNOTATION)
    val exported = members.of(sharedClass)
    val constructor = constructors.constructorOf(sharedClass)
    return ExportedSharedObject(
      name = qualifiedName(sharedClass),
      jsName = annotation.stringArgument(Identifiers.Names.ARG_NAME)?.takeIf { it.isNotEmpty() }
        ?: sharedClass.name.asString(),
      initializer = constructor?.let {
        ExportedInitializer(
          parameters = parameters(it.constructor.parameters, it.arguments.map { plan -> plan.type }),
          accessLevel = it.constructor.visibility.spelling,
        )
      },
      sharedRefNative = sharedRefNativeOf(sharedClass),
      functions = exported.filterIsInstance<BridgedFunction>().map(::function),
      properties = exported.filterIsInstance<BridgedProperty>().map(::property),
      events = exported.filterIsInstance<BridgedEvent>().map(::event),
      file = fileOf(sharedClass),
      accessLevel = sharedClass.visibility.spelling,
    )
  }

  /** The `T` a class passes to `SharedRef<T>`, directly or through its superclasses. */
  private fun sharedRefNativeOf(sharedClass: IrClass): TypeNode? {
    val sharedRef = symbols.classes.sharedRef
    var current: IrClass? = sharedClass
    while (current != null) {
      val superType = current.superTypes.firstOrNull { it.classOrNull?.owner?.kind == ClassKind.CLASS }
        ?: return null
      if (superType.classOrNull == sharedRef) {
        return typeArgumentsOf(superType).singleOrNull()?.let(::nativeType)
      }
      current = superType.classOrNull?.owner
    }
    return null
  }

  private fun function(exported: BridgedFunction): ExportedFunction =
    ExportedFunction(
      name = exported.function.name.asString(),
      jsName = exported.jsName,
      parameters = parameters(exported.function.parameters, exported.arguments.map { it.type }),
      returns = exported.result.takeUnless { it.kind == ValueKind.UNIT }?.let { typeOf(it.type) },
      async = exported.isAsync,
      accessLevel = exported.function.visibility.spelling,
    )

  /**
   * The regular parameters, with their bridged types. A nullable parameter is optional when every
   * parameter after it is nullable too: the bridge fills in null for the trailing ones JavaScript
   * leaves out, and only for those.
   */
  private fun parameters(all: List<IrValueParameter>, types: List<IrType>): List<ExportedParameter> {
    val regular = all.filter { it.kind == IrParameterKind.Regular }
    val firstOmittable = regular.indexOfLast { !it.type.isMarkedNullable() } + 1
    return regular.mapIndexed { index, parameter ->
      ExportedParameter(
        name = parameter.name.asString(),
        type = typeOf(types.getOrElse(index) { parameter.type }),
        optional = index >= firstOmittable,
      )
    }
  }

  private fun property(exported: BridgedProperty): ExportedProperty =
    ExportedProperty(
      name = exported.property.name.asString(),
      jsName = exported.jsName,
      type = typeOf(exported.getterPlan.type),
      readonly = exported.setterPlan == null,
      accessLevel = exported.property.visibility.spelling,
    )

  private fun event(exported: BridgedEvent): ExportedEvent =
    ExportedEvent(
      name = exported.property.name.asString(),
      jsName = exported.jsName,
      payload = exported.payload.takeUnless { it.kind == ValueKind.UNIT }?.let { typeOf(it.type) },
      accessLevel = exported.property.visibility.spelling,
    )

  /** The node for a value of [type], as the bridge converts it. */
  fun typeOf(type: IrType): TypeNode {
    val node = nonNullTypeOf(type)
    return if (type.isMarkedNullable()) TypeNode.Optional(node) else node
  }

  private fun nonNullTypeOf(type: IrType): TypeNode {
    // The frontend rejects every type the bridge cannot carry, so this only fails on a gap in that
    // check; the surface is no reason to fail the compilation, so it reports the type as unknown.
    val kind = try {
      policy.kindOf(type)
    } catch (_: IllegalStateException) {
      return TypeNode.Unknown(spellingOf(type))
    }
    val owner = type.classOrNull?.owner
    val simpleName = owner?.name?.asString() ?: spellingOf(type)

    return when (kind) {
      ValueKind.UNBOXED_SCALAR, ValueKind.BOXED_SCALAR ->
        TypeNode.Primitive(simpleName, if (simpleName == "Boolean") JsType.BOOLEAN else JsType.NUMBER)

      ValueKind.STRING -> TypeNode.Primitive(simpleName, JsType.STRING)

      // The bridge takes and returns any JavaScript value here, which TypeScript spells `unknown`.
      ValueKind.DYNAMIC, ValueKind.UNIT -> TypeNode.Unknown(simpleName)

      ValueKind.PRIMITIVE_ARRAY -> primitiveArray(simpleName)

      ValueKind.JS_HANDLE ->
        if (owner?.kotlinFqName == Identifiers.Classes.JavaScriptObject.asSingleFqName()) {
          TypeNode.Primitive(simpleName, JsType.OBJECT)
        } else {
          TypeNode.Unknown(simpleName)
        }

      // `expo` exports `TypedArray`, the union of the typed array classes, and the renderer
      // imports it from there by this name.
      ValueKind.TYPED_ARRAY -> TypeNode.Ref(TYPED_ARRAY, refKind = null, JsType.OBJECT)

      // A `Duration` crosses as its length in seconds.
      ValueKind.CONVERTED_TO_DOUBLE -> TypeNode.Primitive(simpleName, JsType.NUMBER)
      ValueKind.CONVERTED_TO_STRING -> TypeNode.Primitive(simpleName, JsType.STRING)

      ValueKind.ENUM, ValueKind.INT_ENUM -> {
        val jsType = if (kind == ValueKind.INT_ENUM) JsType.NUMBER else JsType.STRING
        val enumClass = owner!!
        val refKind = RefKind.ENUM.takeIf { enumOf(enumClass) != null }
        TypeNode.Ref(qualifiedName(enumClass), refKind, jsType)
      }

      ValueKind.LIST, ValueKind.SET, ValueKind.ARRAY ->
        TypeNode.ArrayOf(typeArgumentsOf(type).firstOrNull()?.let(::typeOf) ?: TypeNode.Unknown("*"))

      ValueKind.MAP -> {
        val (key, value) = typeArgumentsOf(type).let { it.getOrNull(0) to it.getOrNull(1) }
        TypeNode.Dictionary(
          key = key?.let(::typeOf) ?: TypeNode.Unknown("*"),
          value = value?.let(::typeOf) ?: TypeNode.Unknown("*"),
        )
      }

      ValueKind.RECORD -> {
        recordOf(owner!!)
        TypeNode.Ref(qualifiedName(owner), RefKind.RECORD, JsType.OBJECT)
      }

      ValueKind.SHARED_OBJECT ->
        if (type.classOrNull == symbols.classes.sharedRef) {
          TypeNode.SharedRef(typeArgumentsOf(type).singleOrNull()?.let(::nativeType) ?: TypeNode.Unknown("*"))
        } else {
          val refKind = RefKind.SHARED_OBJECT.takeIf { owner in sharedObjectClasses }
          TypeNode.Ref(qualifiedName(owner!!), refKind, JsType.OBJECT)
        }
    }
  }

  /**
   * A `ByteArray` crosses as an `ArrayBuffer` (and is read from any typed array or `DataView` too);
   * every other primitive array as a plain array of its elements.
   */
  private fun primitiveArray(simpleName: String): TypeNode {
    if (simpleName == "ByteArray") {
      return TypeNode.Ref(ARRAY_BUFFER, refKind = null, JsType.OBJECT)
    }
    val element = simpleName.removeSuffix("Array")
    val jsType = if (element == "Boolean") JsType.BOOLEAN else JsType.NUMBER
    return TypeNode.ArrayOf(TypeNode.Primitive(element, jsType))
  }

  /** The `T` of a `SharedRef<T>`, which never reaches JavaScript: a reference by its spelling. */
  private fun nativeType(type: IrType): TypeNode =
    TypeNode.Ref(spellingOf(type), refKind = null, JsType.OBJECT)

  private fun recordOf(recordClass: IrClass): ExportedRecord? {
    records[recordClass]?.let { return it }
    val constructor = recordClass.primaryConstructor ?: return null
    // Claimed before its fields are described, so a record that names itself ends the walk.
    val placeholder = ExportedRecord(qualifiedName(recordClass), emptyList(), fileOf(recordClass), "")
    records[recordClass] = placeholder

    // The fields are the primary constructor's parameters that are properties, the same ones the
    // codec reads and writes.
    val properties = recordClass.properties.associateBy { it.name }
    val fields = constructor.parameters
      .filter { it.kind == IrParameterKind.Regular && it.name in properties }
      .map { parameter ->
        val nullable = parameter.type.isMarkedNullable()
        ExportedRecordProperty(
          name = parameter.name.asString(),
          type = typeOf(parameter.type),
          optional = nullable,
          required = !nullable && parameter.defaultValue == null,
          accessLevel = properties.getValue(parameter.name).visibility.spelling,
        )
      }
    return ExportedRecord(placeholder.name, fields, placeholder.file, recordClass.visibility.spelling)
      .also { records[recordClass] = it }
  }

  private fun enumOf(enumClass: IrClass): ExportedEnum? {
    if (enumClass in enums) {
      return enums[enumClass]
    }
    return describeEnum(enumClass).also { enums[enumClass] = it }
  }

  /**
   * An enum by the rule `EnumConverter` applies at run time: an entry crosses as its name, or as the
   * value of the enum's only property when that is a `String` or an `Int`. Null when an entry's
   * value is not a constant this compilation can read.
   */
  private fun describeEnum(enumClass: IrClass): ExportedEnum? {
    val entries = enumClass.declarations.filterIsInstance<IrEnumEntry>()
    val fields = enumClass.declarations
      .filterIsInstance<IrProperty>()
      .filter { it.backingField?.isStatic == false }
    val property = fields.singleOrNull()

    val (rawType, values) = when {
      fields.isEmpty() ->
        TypeNode.Primitive("String", JsType.STRING) to entries.map { it.name.asString() }

      property != null && (property.backingField!!.type.isString() || property.backingField!!.type.isInt()) -> {
        val isInt = property.backingField!!.type.isInt()
        val rawType = TypeNode.Primitive(if (isInt) "Int" else "String", if (isInt) JsType.NUMBER else JsType.STRING)
        rawType to (entries.map { entryValue(it, property) ?: return null })
      }

      else -> return null
    }

    return ExportedEnum(
      name = qualifiedName(enumClass),
      rawType = rawType,
      cases = entries.zip(values) { entry, value -> ExportedEnumCase(entry.name.asString(), value) },
      file = fileOf(enumClass),
      accessLevel = enumClass.visibility.spelling,
    )
  }

  /**
   * The constant an entry passes for [property]: the argument of the primary constructor parameter
   * that initializes the property. Null when that is not a literal, or when the entry's arguments
   * are not in this compilation's IR.
   */
  private fun entryValue(entry: IrEnumEntry, property: IrProperty): String? {
    val call = entry.initializerExpression?.expression as? IrEnumConstructorCall ?: return null
    val parameter = (property.backingField?.initializer?.expression as? IrGetValue)
      ?.symbol?.owner as? IrValueParameter
      ?: return null
    val argument = call.arguments.getOrNull(parameter.indexInParameters) as? IrConst ?: return null
    return argument.value?.toString()
  }

  /** The class name with the classes it is nested in, without its package: `Outer.Inner`. */
  private fun qualifiedName(irClass: IrClass): String {
    val fqName = irClass.kotlinFqName.asString()
    val packageName = irClass.getPackageFragment().packageFqName.asString()
    return if (packageName.isEmpty()) fqName else fqName.removePrefix("$packageName.")
  }

  /** The source path of a class this compilation declares, or empty for one from a dependency. */
  private fun fileOf(irClass: IrClass): String = irClass.fileOrNull?.fileEntry?.name.orEmpty()

  private fun typeArgumentsOf(type: IrType): List<IrType> =
    (type as? IrSimpleType)?.arguments?.map { (it as? IrTypeProjection)?.type ?: return emptyList() }.orEmpty()

  private fun spellingOf(type: IrType): String =
    type.classFqName?.shortName()?.asString() ?: type.render()

  private val DescriptorVisibility.spelling: String
    get() = name

  /** The classes in this compilation the surface describes, each kind in source order. */
  private class Declarations : IrVisitorVoid() {
    val modules = mutableListOf<IrClass>()
    val sharedObjects = mutableListOf<IrClass>()
    val records = mutableListOf<IrClass>()
    val enums = mutableListOf<IrClass>()
    val files = mutableSetOf<IrFile>()

    private var file: IrFile? = null

    override fun visitElement(element: IrElement) {
      element.acceptChildrenVoid(this)
    }

    override fun visitFile(declaration: IrFile) {
      file = declaration
      super.visitFile(declaration)
    }

    override fun visitClass(declaration: IrClass) {
      val list = when {
        declaration.hasAnnotation(Identifiers.FqNames.EXPO_MODULE_ANNOTATION) -> modules
        declaration.hasAnnotation(Identifiers.FqNames.SHARED_OBJECT_ANNOTATION) -> sharedObjects
        declaration.hasAnnotation(Identifiers.FqNames.RECORD_ANNOTATION) -> records
        // An enum is declared when it marks itself as one that crosses, even if no export of this
        // compilation names it, so a dependent module's exports can.
        declaration.kind == ClassKind.ENUM_CLASS &&
          declaration.superTypes.any { it.classFqName == Identifiers.FqNames.ENUMERABLE } -> enums
        else -> null
      }
      if (list != null) {
        list += declaration
        file?.let(files::add)
      }
      declaration.acceptChildrenVoid(this)
    }
  }

  private companion object {
    const val TYPED_ARRAY = "TypedArray"
    const val ARRAY_BUFFER = "ArrayBuffer"
  }
}
