package io.github.expo.modules.v2.compiler.typegen

/**
 * The JavaScript surface of one compilation, shaped like the report `ExpoModulesMacros scan-exports`
 * prints for Swift sources.
 *
 * Matching that report is what lets the Swift typegen's renderer, `expo-modules generate-types` in
 * `expo-modules-cli`, write `native.types.ts` for an Android module unchanged: it takes any scanner
 * through `--scanner`, as long as the scanner prints this. The schema's source of truth is
 * `src/types.ts` in `expo/expo-modules-macros`, and [SCHEMA_VERSION] is the version it was written
 * against; the CLI refuses a report with any other.
 *
 * The Swift scan reads syntax, so it reports what a declaration spells. This one is built from
 * resolved IR by the same rules the bridge uses, so it reports what crosses at run time.
 */
internal class ExportedSurface(
  val modules: List<ExportedModule>,
  val sharedObjects: List<ExportedSharedObject>,
  val records: List<ExportedRecord>,
  val enums: List<ExportedEnum>,
) {
  companion object {
    /** The `scan-exports` schema version this report follows. */
    const val SCHEMA_VERSION = 11

    /**
     * The one platform this report covers. The schema lists Apple OSes only, so this is the value
     * an `android` entry in it would take; the renderer does not read it.
     */
    const val PLATFORM = "android"

    /** The `producer` the report names. */
    const val PRODUCER = "expo-modules-v2-android"
  }
}

/** Counts describing the work behind a report, as the schema's `stats` reports them. */
internal class ScanStats(
  /** Source files in the compilation. */
  val filesScanned: Int,
  /** Of those, how many declare something in the surface. */
  val filesParsed: Int,
  val durationMs: Long,
)

/** The JavaScript `typeof` category of a value. */
internal enum class JsType(val spelling: String) {
  BOOLEAN("boolean"),
  NUMBER("number"),
  STRING("string"),
  OBJECT("object"),
}

/** Which array of the surface declares a referenced type. */
internal enum class RefKind(val spelling: String) {
  RECORD("record"),
  SHARED_OBJECT("sharedObject"),
  ENUM("enum"),
}

/** A boundary type as a tagged tree, the schema's `TypeNode`. */
internal sealed interface TypeNode {
  /** The `typeof` category, or null where the schema has none. */
  val jsType: JsType?

  /** A value that crosses as a JS primitive; [name] is the Kotlin spelling, for the reader. */
  class Primitive(val name: String, override val jsType: JsType) : TypeNode

  /** `T?`: reports its wrapped node's category, since `null` is carried by the wrapper. */
  class Optional(val wrapped: TypeNode) : TypeNode {
    override val jsType: JsType? get() = wrapped.jsType
  }

  class ArrayOf(val element: TypeNode) : TypeNode {
    override val jsType: JsType get() = JsType.OBJECT
  }

  class Dictionary(val key: TypeNode, val value: TypeNode) : TypeNode {
    override val jsType: JsType get() = JsType.OBJECT
  }

  /**
   * A named type. With a [refKind], [name] is the `name` of a declaration in the surface. Without
   * one, it is a type the renderer knows by name, such as `TypedArray` or `ArrayBuffer`, or one the
   * surface does not declare. Settled once the whole surface is known, so both can change.
   */
  class Ref(var name: String, var refKind: RefKind?, override val jsType: JsType) : TypeNode

  /** `SharedRef<T>` named by its type argument; [native] is the `T`, which JS never sees. */
  class SharedRef(val native: TypeNode) : TypeNode {
    override val jsType: JsType get() = JsType.OBJECT
  }

  /** A value with no static shape, such as `Any`; [text] is its Kotlin spelling. */
  class Unknown(val text: String) : TypeNode {
    override val jsType: JsType? get() = null
  }
}

/** The parts every declaration and member carries: its Kotlin visibility. */
internal sealed interface Declared {
  /** The Kotlin visibility, such as `public` or `internal`. */
  val accessLevel: String
}

internal class ExportedParameter(
  val name: String,
  val type: TypeNode,
  /** Whether JavaScript may leave it out: a nullable parameter with no non-nullable one after it. */
  val optional: Boolean,
)

internal class ExportedFunction(
  val name: String,
  val jsName: String,
  val parameters: List<ExportedParameter>,
  /** Null for a `Unit` result. For an [async] function, what the promise resolves with. */
  val returns: TypeNode?,
  /** Whether JavaScript gets a promise: a `suspend` function, or one that returns `Promise<T>`. */
  val async: Boolean,
  override val accessLevel: String,
) : Declared

internal class ExportedProperty(
  val name: String,
  val jsName: String,
  val type: TypeNode,
  val readonly: Boolean,
  override val accessLevel: String,
) : Declared

internal class ExportedEvent(
  val name: String,
  val jsName: String,
  /** Null for a `Unit` payload. */
  val payload: TypeNode?,
  override val accessLevel: String,
) : Declared

/** A shared class a module exposes, under the module, to JavaScript's `new`. */
internal class ExportedClass(
  /** The `name` of the shared object it is. */
  val name: String,
  val jsName: String,
)

internal class ExportedModule(
  /** The class name, with the classes it is nested in (`Outer.Module`). */
  val name: String,
  val jsName: String,
  val functions: List<ExportedFunction>,
  val properties: List<ExportedProperty>,
  val events: List<ExportedEvent>,
  val classes: List<ExportedClass>,
  val file: String,
  override val accessLevel: String,
) : Declared

/** The constructor JavaScript's `new` calls. */
internal class ExportedInitializer(
  val parameters: List<ExportedParameter>,
  override val accessLevel: String,
) : Declared

internal class ExportedSharedObject(
  val name: String,
  val jsName: String,
  /** Null when JavaScript cannot construct the class, only receive instances of it. */
  val initializer: ExportedInitializer?,
  /** The `T` of the `SharedRef<T>` the class extends, or null when it is not a shared ref. */
  val sharedRefNative: TypeNode?,
  val functions: List<ExportedFunction>,
  val properties: List<ExportedProperty>,
  val events: List<ExportedEvent>,
  val file: String,
  override val accessLevel: String,
) : Declared

internal class ExportedRecordProperty(
  val name: String,
  val type: TypeNode,
  /** Whether the type is nullable. */
  val optional: Boolean,
  /** Whether JavaScript must supply it: neither nullable nor defaulted. */
  val required: Boolean,
  override val accessLevel: String,
) : Declared

internal class ExportedRecord(
  val name: String,
  val properties: List<ExportedRecordProperty>,
  val file: String,
  override val accessLevel: String,
) : Declared

internal class ExportedEnumCase(
  val name: String,
  /** The value it crosses as: decoded for a string, a decimal literal for an `Int`. */
  val rawValue: String,
)

internal class ExportedEnum(
  val name: String,
  /** What the entries cross as: a `String`, or an `Int`. */
  val rawType: TypeNode,
  val cases: List<ExportedEnumCase>,
  val file: String,
  override val accessLevel: String,
) : Declared
