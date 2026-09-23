package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.ExpoContext
import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.args.Trampoline
import io.github.expo.modules.v2.converters.UrlConverter
import io.github.expo.modules.v2.jsi.JavaScriptObject
import io.github.expo.modules.v2.jsi.JavaScriptRuntime
import io.github.expo.modules.v2.jsi.JavaScriptValue
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.modules.ModuleRegistry
import io.github.expo.modules.v2.records.RecordRegistry
import io.github.expo.modules.v2.records.codecFor
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import io.github.expo.modules.v2.testsupport.TestSupport
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.buffered
import java.net.URI
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.delay

/**
 * List/Map fixture. Buffer-safe complex signatures dispatch through trampolines — hand-written
 * here in exactly the shape a compiler plugin will generate (see [Trampoline] for the contract).
 * [identity] takes `List<Any?>` — a dynamic container is not buffer-safe, so it crosses as a
 * plain JNI object slot and the user method is invoked directly, no trampoline.
 */
private class EchoModule : Module() {
  private val doubleList = AnyType(
    TypeDescriptor.Parametrized(
      List::class.java,
      false,
      arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
    ),
  )
  private val nullableDoubleList = AnyType(
    TypeDescriptor.Parametrized(
      List::class.java,
      false,
      arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, true)),
    ),
  )
  private val intMap = AnyType(
    TypeDescriptor.Parametrized(
      Map::class.java,
      false,
      arrayOf(
        TypeDescriptor.Simple(String::class.java, false),
        TypeDescriptor.Simple(Int::class.javaObjectType, false),
      ),
    ),
  )
  private val nullableStringMap = AnyType(
    TypeDescriptor.Parametrized(
      Map::class.java,
      false,
      arrayOf(TypeDescriptor.Simple(String::class.java, false), TypeDescriptor.Simple(String::class.java, true)),
    ),
  )

  fun identity(values: List<Any?>): List<Any?> = values

  fun sum(values: List<Double>): Double = values.sum()

  fun sum__trampoline(payloadLength: Int): Double {
    val args = Trampoline.arguments(payloadLength)
    val values = try {
      args.next<List<Double>>(doubleList.descriptor)
    } finally {
      args.finish()
    }
    return sum(values)
  }

  fun total(map: Map<String, Int>): Int = map.values.sum()

  fun total__trampoline(payloadLength: Int): Int {
    val args = Trampoline.arguments(payloadLength)
    val map = try {
      args.next<Map<String, Int>>(intMap.descriptor)
    } finally {
      args.finish()
    }
    return total(map)
  }

  fun holes(values: List<Double?>): List<Double?> = values

  fun holes__trampoline(payloadLength: Int): Int {
    val args = Trampoline.arguments(payloadLength)
    val values = try {
      args.next<List<Double?>>(nullableDoubleList.descriptor)
    } finally {
      args.finish()
    }
    return Trampoline.writeResult(holes(values), nullableDoubleList.descriptor)
  }

  fun labels(map: Map<String, String?>): Map<String, String?> = map

  fun labels__trampoline(payloadLength: Int): Int {
    val args = Trampoline.arguments(payloadLength)
    val map = try {
      args.next<Map<String, String?>>(nullableStringMap.descriptor)
    } finally {
      args.finish()
    }
    return Trampoline.writeResult(labels(map), nullableStringMap.descriptor)
  }
}

/**
 * Record fixture. `label` carries no Kotlin default on purpose: a default would make the field
 * optional, and an optional field changes the inbound payload grammar (C++ writes a presence byte
 * per optional field). [Tagged] and [Defaults] cover that grammar instead.
 */
@Record
private data class Point(val x: Double, val y: Double, val label: String?) : io.github.expo.modules.v2.records.Record

private class RecordModule : Module() {
  private val pointList = AnyType(
    TypeDescriptor.Parametrized(List::class.java, false, arrayOf(TypeDescriptor.Simple(Point::class.java, false))),
  )

  fun move(point: Point): Point = Point(point.x + 1.0, point.y + 1.0, point.label)

  fun move__trampoline(payloadLength: Int): Int {
    val args = Trampoline.arguments(payloadLength)
    val point = try {
      args.next<Point>(TypeDescriptor.Simple(Point::class.java, false))
    } finally {
      args.finish()
    }
    return Trampoline.writeResult(move(point), TypeDescriptor.Simple(Point::class.java, false))
  }

  fun label(point: Point): String = point.label ?: "<none>"

  fun label__trampoline(payloadLength: Int): String {
    val args = Trampoline.arguments(payloadLength)
    val point = try {
      args.next<Point>(TypeDescriptor.Simple(Point::class.java, false))
    } finally {
      args.finish()
    }
    return label(point)
  }

  fun spread(points: List<Point>): List<Point> = points

  fun spread__trampoline(payloadLength: Int): Int {
    val args = Trampoline.arguments(payloadLength)
    val points = try {
      args.next<List<Point>>(pointList.descriptor)
    } finally {
      args.finish()
    }
    return Trampoline.writeResult(spread(points), pointList.descriptor)
  }

  fun explode(count: Int): List<Point> = List(count) { Point(it.toDouble(), it * 2.0, null) }

  // No payload parameter -> no payloadLength and no argument-overflow branch; only the buffered
  // RESULT rides the buffer (and may still overflow out-of-band inside writeResult).
  fun explode__trampoline(count: Int): Int = Trampoline.writeResult(explode(count), pointList.descriptor)

  fun make(): Point = Point(1.5, 2.5, "made")

  fun make__trampoline(): Int = Trampoline.writeResult(make(), TypeDescriptor.Simple(Point::class.java, false))
}

@Record
private data class MyRecord(val x: Int, val b: String) : io.github.expo.modules.v2.records.Record

/** An optional field, so an inbound payload gates `note` with a presence byte. */
@Record
private data class Tagged(val v: Int, val note: String? = null) : io.github.expo.modules.v2.records.Record

/**
 * Optional fields with NON-null defaults, so absent, `null` and a value are three visibly distinct
 * outcomes end to end. `tag` is mandatory and `trailing` follows the optional fields, which pins
 * the field cursor on the map path.
 */
@Record
private data class Defaults(
  val tag: String,
  val count: Int = 5,
  val label: String? = "z",
  val trailing: Int = 7,
) : io.github.expo.modules.v2.records.Record

/** Both crossing shapes for [Defaults]: the binary payload and the `Map` bridge. */
@ExpoModule(name = "Defaults")
private class DefaultsModule : Module() {
  /** Buffered: native writes a presence byte per optional field. */
  @JS
  fun describe(value: Defaults): String =
    "${value.tag}/${value.count}/${value.label}/${value.trailing}"

  /**
   * The same record over the other crossing shape. `Buffer.NO` sends it as a `Map<String, Any?>`, so
   * an absent optional field is simply a key native left out — one Kotlin function per transport,
   * because a single one has exactly one.
   */
  @JS(name = "describeMap")
  @BufferMode(Buffer.NO)
  fun describeMapped(value: Defaults): String = describe(value)
}


private const val WIDE_FIELDS = 42

/**
 * The widest record shape measured across expo's 345 records (`TextFieldColorsRecord`, 42 fields).
 * Guards the per-field work in the pull transport: 42 `String[]` elements, each a local ref and a
 * `toStdString`, plus 42 type-code groups read out of one `int[]`.
 */
@Record
private data class Wide(
  val field0: Int,
  val field1: Int,
  val field2: Int,
  val field3: Int,
  val field4: Int,
  val field5: Int,
  val field6: Int,
  val field7: Int,
  val field8: Int,
  val field9: Int,
  val field10: Int,
  val field11: Int,
  val field12: Int,
  val field13: Int,
  val field14: Int,
  val field15: Int,
  val field16: Int,
  val field17: Int,
  val field18: Int,
  val field19: Int,
  val field20: Int,
  val field21: Int,
  val field22: Int,
  val field23: Int,
  val field24: Int,
  val field25: Int,
  val field26: Int,
  val field27: Int,
  val field28: Int,
  val field29: Int,
  val field30: Int,
  val field31: Int,
  val field32: Int,
  val field33: Int,
  val field34: Int,
  val field35: Int,
  val field36: Int,
  val field37: Int,
  val field38: Int,
  val field39: Int,
  val field40: Int,
  val field41: Int,
) : io.github.expo.modules.v2.records.Record

@ExpoModule(name = "Wide")
private class WideModule : Module() {
  @JS
  fun echo(value: Wide): Wide = Wide(value.field0 + 1, value.field1 + 1, value.field2 + 1, value.field3 + 1, value.field4 + 1, value.field5 + 1, value.field6 + 1, value.field7 + 1, value.field8 + 1, value.field9 + 1, value.field10 + 1, value.field11 + 1, value.field12 + 1, value.field13 + 1, value.field14 + 1, value.field15 + 1, value.field16 + 1, value.field17 + 1, value.field18 + 1, value.field19 + 1, value.field20 + 1, value.field21 + 1, value.field22 + 1, value.field23 + 1, value.field24 + 1, value.field25 + 1, value.field26 + 1, value.field27 + 1, value.field28 + 1, value.field29 + 1, value.field30 + 1, value.field31 + 1, value.field32 + 1, value.field33 + 1, value.field34 + 1, value.field35 + 1, value.field36 + 1, value.field37 + 1, value.field38 + 1, value.field39 + 1, value.field40 + 1, value.field41 + 1)
}

@Record
private data class Pulled(val n: Int, val tag: String, val note: String?) : io.github.expo.modules.v2.records.Record

/** Fixture for the pull path: this codec's schema reaches native through [RecordRegistry.fetchSchema]. */
@ExpoModule(name = "Pull")
private class PulledModule : Module() {
  @JS
  fun echo(value: Pulled): Pulled = Pulled(value.n + 1, value.tag, value.note)
}

/**
 * Dynamic-return fixture: an `ANY` result crosses as a plain JNI object (no trampoline, no
 * buffer). A record instance found inside it is decomposed by runtime class — native up-calls
 * `RecordRegistry.dynamicRecordToMap`, which needs the codec registered. The generated codec
 * registers from the class's static init, so constructing the record — inside the user method,
 * before the result converts — guarantees registration.
 */
@Record
private data class Sticker(val id: Int, val label: String) : io.github.expo.modules.v2.records.Record

@ExpoModule(name = "Dyn")
private class DynamicRecordModule : Module() {
  @JS
  fun make(): Any = mapOf("kind" to "sticker", "value" to Sticker(7, "seven"))
}

/** Registered like [Sticker], but deliberately only ever constructed mid-conversion (see below). */
@Record
private data class Rogue(val n: Int) : io.github.expo.modules.v2.records.Record

@ExpoModule(name = "Late")
private class LateRegistrationModule : Module() {
  // A lazy list constructs Rogue for the FIRST time while the result is already being converted
  // element-wise. The ANY object-slot path decomposes records with a Kotlin up-call and never
  // touches native schemas, so even this late registration works: the class init runs when the
  // element is first read, before its own decomposition is attempted.
  @JS
  fun late(): Any = object : AbstractList<Any>() {
    override val size get() = 1
    override fun get(index: Int): Any = Rogue(42)
  }

  /** Never registered anywhere: converting it must fail with a descriptive error. */
  @JS
  fun stranger(): Any = listOf(Unregistered(1))
}

private class Unregistered(@Suppress("unused") val n: Int)

/**
 * Non-buffer-safe record fixture: [Listener] declares JSI-handle fields, so it never rides the
 * binary buffer — it crosses JNI as a `Map<String, Any?>` object slot. C++ builds the argument
 * map schema-directed (handle fields stay live references); the codec's own [RecordCodec.fromMap]
 * / [RecordCodec.toMap] rebuild and decompose the typed record, verifying as they go — no
 * schema-directed reader/writer is involved on this path.
 */
@Record(bufferSafe = false)
private data class Listener(
  val name: String,
  val target: JavaScriptObject,
  val extra: JavaScriptValue,
  val backup: JavaScriptObject?,
) : io.github.expo.modules.v2.records.Record

private class HandleRecordModule : Module() {
  fun attach(listener: Listener): Listener {
    listener.target.setProperty("attached", true)
    return listener
  }

  // Map-riding only: no payload parameters, so no payloadLength in the trampoline signature.
  fun attach__trampoline(listener: Map<String, Any?>): Map<String, Any?> =
    codecFor<Listener>().toMap(attach(codecFor<Listener>().fromMap(listener)))

  fun extraKind(listener: Listener): String = listener.extra.kind().name

  fun extraKind__trampoline(listener: Map<String, Any?>): String =
    extraKind(codecFor<Listener>().fromMap(listener))

  /** Mixed shape: payload args (buffer-safe record + typed list) alongside object slots. */
  fun mixed(prefix: String, rec: MyRecord, flagged: Listener, xs: List<Double>, dyn: Any?): Listener {
    flagged.target.setProperty("sum", xs.sum())
    return flagged.copy(name = prefix + rec.b + (dyn ?: "-"))
  }

  fun mixed__trampoline(
    prefix: String,
    flagged: Map<String, Any?>,
    dyn: Any?,
    payloadLength: Int,
  ): Map<String, Any?> {
    val doubleList = AnyType(
      TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
      ),
    )
    val args = Trampoline.arguments(payloadLength)
    val rec: MyRecord
    val xs: List<Double>
    try {
      rec = args.next(TypeDescriptor.Simple(MyRecord::class.java, false))
      xs = args.next(doubleList.descriptor)
    } finally {
      args.finish()
    }
    return codecFor<Listener>().toMap(mixed(prefix, rec, codecFor<Listener>().fromMap(flagged), xs, dyn))
  }

  /** A list of flagged records: crosses as a JNI List whose elements are Maps. */
  fun first(listeners: List<Listener>): Listener = listeners.first()

  fun first__trampoline(listeners: List<Map<String, Any?>>): Map<String, Any?> =
    codecFor<Listener>().toMap(first(listeners.map(codecFor<Listener>()::fromMap)))
}

/** Dynamic (`Any`) values carrying live handles: pure JNI object slots, no trampoline at all. */
@ExpoModule(name = "DynH")
private class DynamicHandleModule : Module() {
  @JS
  fun wrap(obj: JavaScriptObject): Any = mapOf("original" to obj, "n" to 1)

  @JS
  fun passthrough(value: Any?): Any? = value
}

/**
 * JSI-handle fixture: a JS_VALUE / JS_OBJECT slot passes a live reference into the runtime — the
 * method receives a [JavaScriptValue]/[JavaScriptObject] handle over plain JNI (no trampoline,
 * no payload) and can hand one back, where it unwraps to the same underlying JS value.
 */
@ExpoModule(name = "Js")
private class JsHandleModule : Module() {
  @JS
  fun kindOf(value: JavaScriptValue): String = value.kind().name

  @JS
  fun echo(value: JavaScriptValue): JavaScriptValue = value

  @JS
  fun stamp(obj: JavaScriptObject): JavaScriptObject {
    obj.setProperty("stamped", true)
    return obj
  }

  @JS
  fun firstName(obj: JavaScriptObject): String = obj.getPropertyNames().first()
}

/**
 * Trampoline fixture: hand-written `__trampoline` siblings in exactly the shape a compiler
 * plugin will generate (see [io.github.expo.modules.v2.args.Trampoline] for the contract). Each JS
 * call dispatches to the trampoline in a single JNI transition.
 */
private class TrampolineModule : Module() {
  fun record(value: MyRecord): MyRecord = value

  // A buffer-safe record declared WITHOUT buffered() crosses as a Map object slot; the
  // trampoline rebuilds/decomposes it via the codec, exactly like an inherently
  // non-buffer-safe record.
  fun mapRide(value: MyRecord): MyRecord = MyRecord(value.x + 1, value.b + "!")

  fun mapRide__trampoline(value: Map<String, Any?>): Map<String, Any?> =
    codecFor<MyRecord>().toMap(mapRide(codecFor<MyRecord>().fromMap(value)))

  fun record__trampoline(payloadLength: Int): Int {
    val args = Trampoline.arguments(payloadLength)
    val value = try {
      args.next<MyRecord>(TypeDescriptor.Simple(MyRecord::class.java, false))
    } finally {
      args.finish()
    }
    return Trampoline.writeResult(record(value), TypeDescriptor.Simple(MyRecord::class.java, false))
  }

  fun tag(prefix: String, value: MyRecord, times: Int): String =
    prefix + value.b.repeat(times) + value.x

  fun tag__trampoline(prefix: String, times: Int, payloadLength: Int): String {
    val args = Trampoline.arguments(payloadLength)
    val value = try {
      args.next<MyRecord>(TypeDescriptor.Simple(MyRecord::class.java, false))
    } finally {
      args.finish()
    }
    return tag(prefix, value, times)
  }

  fun combine(a: MyRecord, b: Tagged): MyRecord = MyRecord(a.x + b.v, a.b + (b.note ?: ""))

  fun combine__trampoline(payloadLength: Int): Int {
    val args = Trampoline.arguments(payloadLength)
    val a: MyRecord
    val b: Tagged
    try {
      a = args.next(TypeDescriptor.Simple(MyRecord::class.java, false))
      b = args.next(TypeDescriptor.Simple(Tagged::class.java, false))
    } finally {
      args.finish()
    }
    return Trampoline.writeResult(combine(a, b), TypeDescriptor.Simple(MyRecord::class.java, false))
  }

  fun make(x: Int): MyRecord = MyRecord(x, "made")

  // Only the RESULT is buffered: no payload parameter means no payloadLength in the signature.
  fun make__trampoline(x: Int): Int =
    Trampoline.writeResult(make(x), TypeDescriptor.Simple(MyRecord::class.java, false))

  fun boom(value: MyRecord): MyRecord = error("boom: ${value.b}")

  fun boom__trampoline(payloadLength: Int): Int {
    val args = Trampoline.arguments(payloadLength)
    val value = try {
      args.next<MyRecord>(TypeDescriptor.Simple(MyRecord::class.java, false))
    } finally {
      args.finish()
    }
    return Trampoline.writeResult(boom(value), TypeDescriptor.Simple(MyRecord::class.java, false))
  }
}

@Record
private data class Bookmark(val url: URL, val ttl: Duration?) : io.github.expo.modules.v2.records.Record

// A codec or trampoline names the converter of the declaration it serves, nullability included.
private val urlConverter = UrlConverter(isNullable = false)

// Shared declarations for the built-in converted types.
private val urlDescriptor = TypeDescriptor.Simple(URL::class.java, false)
private val nullableDurationDescriptor = TypeDescriptor.Simple(Duration::class.java, true)
private val urlType = AnyType(urlDescriptor)
private val nullableDurationType = AnyType(nullableDurationDescriptor)
/**
 * Built-in converted-type fixture: user methods take/return `URL` and `Duration`, while JS and the
 * JNI signatures only ever see their bridge types.
 */
@ExpoModule(name = "Conv")
private class ConvertedModule : Module() {
  // Pinned to JNI slots: every bridge value stays in its own slot, so these trampolines take no
  // payloadLength and have no argument-overflow branch — the conversion happens inline.
  @JS
  @BufferMode(Buffer.NO)
  fun host(url: URL): String = url.host

  @JS
  @BufferMode(Buffer.NO)
  fun home(): URL = URI("https://expo.dev/home").toURL()

  @JS
  @BufferMode(Buffer.NO)
  fun total(a: Duration, b: Duration): Duration = a + b

  @JS
  @BufferMode(Buffer.NO)
  fun describe(url: URL?): String = url?.host ?: "none"

  // Left on the defaults, which mixes both: the record rides the payload while the Duration bridges
  // as an unboxed Double in its own slot.
  @JS
  fun extend(bookmark: Bookmark, extra: Duration): Bookmark =
    bookmark.copy(ttl = (bookmark.ttl ?: Duration.ZERO) + extra)
}


/**
 * Native-property fixture. The `__trampoline` accessors are intentionally hardcoded functions in
 * exactly the function-trampoline shape a compiler plugin can generate later: a getter is a 0-arg
 * trampoline and a setter a 1-arg Unit trampoline, so scalar conversions expose their bridge type
 * while buffer-riding values ride the payload.
 */
/**
 * Leaf fixtures: String, boxed scalars and primitive arrays declared buffered() ride the
 * trampoline payload instead of their usual JNI slots.
 */
private class BufferedLeafModule : Module() {
  private val string = AnyType(TypeDescriptor.Simple(String::class.java, false))
  private val nullableInt = AnyType(TypeDescriptor.Simple(Int::class.javaObjectType, true))
  private val doubleList = AnyType(
    TypeDescriptor.Parametrized(
      List::class.java,
      false,
      arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
    ),
  )

  fun shout(value: String): String = value.uppercase()

  fun shout__trampoline(payloadLength: Int): Int {
    val args = Trampoline.arguments(payloadLength)
    val value = try {
      args.nextString()
    } finally {
      args.finish()
    }
    return Trampoline.writeResult(shout(value), string.descriptor)
  }

  fun bump(value: Int?): Int? = value?.plus(1)

  fun bump__trampoline(payloadLength: Int): Int {
    val args = Trampoline.arguments(payloadLength)
    val value = try {
      args.nextIntOrNull()
    } finally {
      args.finish()
    }
    return Trampoline.writeResult(bump(value), nullableInt.descriptor)
  }

  fun addOne(values: IntArray): IntArray = IntArray(values.size) { values[it] + 1 }

  fun addOne__trampoline(payloadLength: Int): Int {
    val args = Trampoline.arguments(payloadLength)
    val values = try {
      args.nextIntArray()
    } finally {
      args.finish()
    }
    return Trampoline.writeResult(addOne(values), TypeDescriptor.IntArray(false))
  }

  fun reverseBytes(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { bytes[bytes.size - 1 - it] }

  fun reverseBytes__trampoline(payloadLength: Int): Int {
    val args = Trampoline.arguments(payloadLength)
    val bytes = try {
      args.nextByteArray()
    } finally {
      args.finish()
    }
    return Trampoline.writeResult(reverseBytes(bytes), TypeDescriptor.ByteArray(false))
  }

  // Mixed transports in one signature: prefix and n keep their JNI slots; xs and s ride the
  // payload positionally, in declared order.
  fun label(prefix: String, xs: List<Double>, n: Int, s: String): String =
    "$prefix|${xs.sum()}|$n|$s"

  fun label__trampoline(prefix: String, n: Int, payloadLength: Int): Int {
    val args = Trampoline.arguments(payloadLength)
    val xs: List<Double>
    val s: String
    try {
      xs = args.next(doubleList.descriptor)
      s = args.nextString()
    } finally {
      args.finish()
    }
    return Trampoline.writeResult(label(prefix, xs, n, s), string.descriptor)
  }

  // A leaf-bridge converter declared buffered() at the use site: the String bridge rides the
  // payload; the direct String return keeps its JNI slot.
  fun visit(url: URL): String = url.host

  fun visit__trampoline(payloadLength: Int): String {
    val args = Trampoline.arguments(payloadLength)
    val url = try {
      args.next<URL>(urlDescriptor)
    } finally {
      args.finish()
    }
    return visit(url)
  }
}

/**
 * Properties whose accessors the plugin generates, covering every transport a property value can
 * take. [PropertyModule] is the hand-written equivalent; this is the same contract reached through
 * generated trampolines.
 */
@ExpoModule(name = "GenProps")
private class GeneratedPropertyModule : Module() {
  /** A read in a JNI slot, which is where a String is fastest on the way out. A `val` has no setter. */
  @JS
  val version: String = "1.0"

  /** An unboxed scalar needs no trampoline at all, so both Kotlin accessors are exported directly. */
  @JS
  var count: Int = 1

  /** Kotlin's `is` prefix rule: the accessor is `isReady`, never `getIsReady`. */
  @JS
  val isReady: Boolean = true

  /** A split pair: the setter reads the payload, the getter returns a String in its slot. */
  @JS
  var motto: String = "carpe diem"

  /** A converted value, whose String bridge also rides the buffer. */
  @JS
  var homepage: URL = URI("https://expo.dev/home").toURL()

  /** A buffered container, and the one that overflows the fixed buffer under test. */
  @JS
  var values: List<Int> = listOf(1, 2)

  /** A boxed scalar, so null is a distinct value in both directions. */
  @JS
  var nullableCount: Int? = null
}

/**
 * One shared receiver for the hand-written registrations below. Several of them deliberately export
 * the same instance under more than one module name, which is the property they test.
 */
private val sharedMath = MathUtils()
private val sharedEcho = EchoModule()
private val sharedRecord = RecordModule()
private val sharedHandleRecord = HandleRecordModule()
private val sharedTrampoline = TrampolineModule()
private val sharedBufferedLeaf = BufferedLeafModule()

private fun ModuleRegistry.registerRecordFixture(
  name: String = "Rec",
  module: RecordModule = RecordModule(),
) {
  register(name, module) {
    function(
      "move",
      AnyType(TypeDescriptor.Simple(Point::class.java, false)).buffered(),
      returns = AnyType(TypeDescriptor.Simple(Point::class.java, false)).buffered(),
      methodName = "move__trampoline",
    )
    function(
      "label",
      AnyType(TypeDescriptor.Simple(Point::class.java, false)).buffered(),
      returns = AnyType(TypeDescriptor.Simple(String::class.java, false)),
      methodName = "label__trampoline",
    )
    function(
      "spread",
      AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(Point::class.java, false)),
        ),
      ).buffered(),
      returns = AnyType(
        TypeDescriptor.Parametrized(
          List::class.java,
          false,
          arrayOf(TypeDescriptor.Simple(Point::class.java, false)),
        ),
      ).buffered(),
      methodName = "spread__trampoline",
    )
    function(
      "make",
      returns = AnyType(TypeDescriptor.Simple(Point::class.java, false)).buffered(),
      methodName = "make__trampoline",
    )
  }
}

/** Not annotated, so `register(module)` has no definition to read. */
private class UnannotatedModule : Module()

/**
 * The buffered leaf readers, reached through generated trampolines rather than hand-written ones.
 *
 * Every argument here rides the payload, so each case exercises one typed `TrampolineArguments`
 * reader — `nextIntOrNull`, `nextStringOrNull`, `nextIntArray` and friends — which is the fast path
 * the plugin picks over the descriptor-driven `next(schema)`.
 */
@ExpoModule(name = "GenLeaves")
private class GeneratedLeafModule : Module() {
  @JS
  fun echoInt(value: Int?): Int? = value

  @JS
  fun echoLong(value: Long?): Long? = value

  @JS
  fun echoFloat(value: Float?): Float? = value

  @JS
  fun echoDouble(value: Double?): Double? = value

  @JS
  fun echoBoolean(value: Boolean?): Boolean? = value

  @JS
  fun echoNullableString(value: String?): String? = value

  /** `Buffer.YES` on the one shape the default declines, because a bulk region copy usually wins. */
  @JS
  @BufferMode(Buffer.YES)
  fun sumBuffered(values: IntArray): Int = values.sum()

  @JS
  @BufferMode(Buffer.YES)
  fun echoBufferedDoubles(values: DoubleArray): DoubleArray = values

  /** Eight buffered arguments in one payload, read back in declared order. */
  @JS
  fun join(
    a: String,
    b: String,
    c: String,
    d: String,
    e: String,
    f: String,
    g: String,
    h: String,
  ): String = "$a$b$c$d$e$f$g$h"

  /** Throws after its payload has been read, so the buffer must be left usable. */
  @JS
  fun boom(value: String): String = error("boom: $value")
}

/**
 * A nullable record through a generated trampoline. The runtime has always supported the shape; no
 * generated code exercised it until now.
 */
@ExpoModule(name = "GenNullableRecord")
private class GeneratedNullableRecordModule : Module() {
  @JS
  fun echo(value: Sticker?): Sticker? = value

  @JS
  fun describe(value: Sticker?): String = value?.let { "${it.id}/${it.label}" } ?: "none"

  @JS
  fun makeOrNull(present: Boolean): Sticker? = if (present) Sticker(7, "seven") else null
}

private class PropertyModule : Module() {
  private val intList = AnyType(
    TypeDescriptor.Parametrized(
      List::class.java,
      false,
      arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
    ),
  )

  val version = "1.0"
  var count = 1
  val isReady = true

  var homepage: URL = URI("https://expo.dev/home").toURL()

  // Converter accessors: the String bridge keeps its JNI slot, so neither accessor takes a
  // payloadLength.
  fun getHomepage__trampoline(): String = urlConverter.toJni(homepage) as String

  fun setHomepage__trampoline(value: String) {
    homepage = requireNotNull(urlConverter.fromJni(value))
  }

  var motto: String = "carpe diem"

  // A buffered getter has no payload parameters: no payloadLength, just the result write.
  fun getMotto__trampoline(): Int =
    Trampoline.writeResult(motto, TypeDescriptor.Simple(String::class.java, false))

  fun setMotto__trampoline(payloadLength: Int) {
    val args = Trampoline.arguments(payloadLength)
    val next = try {
      args.nextString()
    } finally {
      args.finish()
    }
    motto = next
  }

  var values: List<Int> = listOf(1, 2)

  fun getValues__trampoline(): Int = Trampoline.writeResult(values, intList.descriptor)

  fun setValues__trampoline(payloadLength: Int) {
    val args = Trampoline.arguments(payloadLength)
    val next = try {
      args.next<List<Int>>(intList.descriptor)
    } finally {
      args.finish()
    }
    values = next
  }
}

/** Stateful, non-singleton receiver for the native-state pinning test. */
@ExpoModule(name = "Counter")
private class CounterFixture : Module() {
  private var count = 0

  @JS
  fun increment(): Int {
    count += 1
    return count
  }
}

/** Exercised by the primitive-array signature test (bulk JNI copies, zero boxing). */
@ExpoModule(name = "Arr")
private class PrimitiveArrayModule : Module() {
  @JS
  fun echoDoubles(values: DoubleArray): DoubleArray = values

  @JS
  fun echoInts(values: IntArray): IntArray = values

  @JS
  fun sum(values: DoubleArray): Double = values.sum()

  @JS
  fun reverseBytes(data: ByteArray): ByteArray = data.reversedArray()

  @JS
  fun flags(values: BooleanArray): BooleanArray = values
}

@ExpoModule(name = "Units")
private class UnitModule : Module() {
  private val unitList = AnyType(
    TypeDescriptor.Parametrized(List::class.java, false, arrayOf(TypeDescriptor.Simple(Unit::class.java, false))),
  )

  @JS
  fun accept(@Suppress("UNUSED_PARAMETER") value: Unit): String = "unit"

  @JS
  fun clear(): Unit = Unit

  @JS
  fun dynamic(): Any = Unit

  @JS
  fun echo(values: List<Unit>): List<Unit> = values
}

@ExpoModule(name = "NullableScalars")
@BufferMode(Buffer.NO)
private class NullableScalarModule : Module() {
  @JS
  var count: Int? = null

  @JS
  fun echoBoolean(value: Boolean?): Boolean? = value
  @JS
  fun echoInt(value: Int?): Int? = value
  @JS
  fun echoLong(value: Long?): Long? = value
  @JS
  fun echoFloat(value: Float?): Float? = value
  @JS
  fun echoDouble(value: Double?): Double? = value
}

/**
 * `suspend` exports, covering the three ways a body can finish: inline, after a real suspension, and
 * by throwing. Everything here is generated by `@JS` — the trampolines, the promise, the transport.
 */
@ExpoModule(name = "Async")
private class AsyncModule : Module() {
  @JS
  suspend fun immediate(value: Int): String = "immediate:$value"

  @JS
  suspend fun delayed(value: Int): String {
    // A real suspension point, so the body resumes on a coroutine thread rather than the JS one.
    delay(5)
    return "delayed:$value"
  }

  @JS
  suspend fun boom(): String = throw IllegalStateException("kaboom")

  /** A buffered argument, decoded eagerly before the coroutine starts. */
  @JS
  suspend fun sum(values: List<Int>): Int {
    delay(1)
    return values.sum()
  }

  /** A scalar pinned to a JNI slot, so the result crosses boxed rather than on the buffer. */
  @JS
  @BufferMode(returns = Buffer.NO)
  suspend fun twice(value: Int): Int = value * 2

  @JS
  suspend fun nothing(): Unit = delay(1)

  /** Too big for the shared buffer, so the result takes the overflow slot instead. */
  @JS
  suspend fun huge(size: Int): String {
    delay(1)
    return "x".repeat(size)
  }

  /** Never completes, so its promise is still pending when the runtime closes. */
  @JS
  suspend fun forever(): String {
    delay(Long.MAX_VALUE)
    return "unreachable"
  }
}

private val sharedAsync = AsyncModule()

/** Runs the JS thread until [script] stops evaluating to `null`, then returns it as a string. */
private fun HermesRuntime.awaitSettled(script: String): String {
  runEventLoop { !evaluate("$script === null").getBool() }
  return evaluateAsString(script)
}

class HermesRuntimeTest {
  companion object {
    init {
      // Wire kolibri's native bindings to the .dylib before the first native-backed class loads.
      ExpoHermes.ensureLoaded()
    }
  }

  @Test
  fun `evaluate returns a JavaScriptValue`() {
    HermesRuntime().use { runtime ->
      val number = runtime.evaluate("21 * 2")
      assertTrue(number.isNumber())
      assertEquals(42.0, number.getDouble())

      val objectValue = runtime.evaluate("({answer: 42})")
      assertTrue(objectValue.isObject())
      assertEquals(42, objectValue.getObject().getProperty("answer").getInt())
    }
  }

  @Test
  fun `exposes the ExpoModulesCore host object`() {
    HermesRuntime().use { runtime ->
      assertEquals("hermes", runtime.evaluateAsString("ExpoModulesCore.engine"))
      assertEquals("2.0.0-alpha", runtime.evaluateAsString("ExpoModulesCore.apiVersion"))
      // The production core object carries no test/benchmark hooks — those live on the
      // ExpoTestSupport object that :test-support installs on demand.
      assertEquals(
        "0",
        runtime.evaluateAsString(
          "Object.keys(ExpoModulesCore).filter(k => k.startsWith('__')).length",
        ),
      )
    }
  }

  @Test
  fun `ExpoModulesCore materializes lazily and stays enumerable`() {
    HermesRuntime().use { runtime ->
      // The React devtools \$\$typeof probe answers undefined WITHOUT materializing the backed
      // object (LazyObject.get's early return), so it must run before any other access.
      assertEquals("undefined", runtime.evaluateAsString("ExpoModulesCore.\$\$typeof"))
      // Enumeration goes through LazyObject.getPropertyNames and must see the backed object's
      // own properties.
      assertEquals(
        "true",
        runtime.evaluateAsString("Object.keys(ExpoModulesCore).includes('nativeLog')"),
      )
    }
  }

  @Test
  fun `invokes a native host function from JS`() {
    HermesRuntime().use { runtime ->
      assertEquals(
        "undefined",
        runtime.evaluateAsString("ExpoModulesCore.nativeLog('from test')"),
      )
    }
  }

  @Test
  fun `Unit accepts any present non-null input and returns JavaScript undefined`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(UnitModule())

      val script = """
        (() => {
          const units = expo.modules.Units;
          const throws = callback => {
            try { callback(); return false; } catch (_) { return true; }
          };
          const echoed = units.echo([123, 'anything']);
          return [
            units.accept(123),
            throws(() => units.accept()),
            throws(() => units.accept(null)),
            typeof units.clear(),
            typeof units.dynamic(),
            echoed.length === 2 && echoed.every(value => value === undefined),
            throws(() => units.echo([null])),
          ].join('|');
        })()
      """.trimIndent()
      assertEquals(
        "unit|true|true|undefined|undefined|true|true",
        runtime.evaluateAsString(script),
      )
    }
  }

  @Test
  fun `nullable primitive JNI slots use boxed values`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(NullableScalarModule())

      assertEquals(
        "true",
        runtime.evaluateAsString(
          """
          (() => {
            const scalars = expo.modules.NullableScalars;
            const roundTrips =
              scalars.echoBoolean(true) === true && scalars.echoBoolean(null) === null &&
              scalars.echoInt(42) === 42 && scalars.echoInt(null) === null &&
              scalars.echoLong(43) === 43 && scalars.echoLong(null) === null &&
              scalars.echoFloat(1.25) === 1.25 && scalars.echoFloat(null) === null &&
              scalars.echoDouble(2.5) === 2.5 && scalars.echoDouble(null) === null;
            scalars.count = 7;
            const presentProperty = scalars.count === 7;
            scalars.count = null;
            return roundTrips && presentProperty && scalars.count === null;
          })()
          """.trimIndent(),
        ),
      )
    }
  }

  @Test
  fun `the conversion engine pins its slot contracts`() {
    HermesRuntime().use { runtime ->
      TestSupport.install(runtime)

      // Head-position null on a non-nullable type throws: nullability is a declaration, and the
      // transport does not get a say in it.
      assertEquals(
        "threw:non-nullable",
        runtime.evaluateAsString(
          "try { ExpoTestSupport.__convertRoundTrip(null, [${CppType.DOUBLE.code}]); 'no'; } " +
            "catch (e) { 'threw:' + (e.message.includes('non-nullable') ? 'non-nullable' : e.message); }",
        ),
      )

      // Element-position null throws too, and the error names the non-nullable slot.
      assertEquals(
        "threw:non-nullable",
        runtime.evaluateAsString(
          "try { ExpoTestSupport.__convertRoundTrip([1.5, null], [${CppType.LIST.code}, ${CppType.DOUBLE.code}]); 'no'; } " +
            "catch (e) { 'threw:' + (e.message.includes('non-nullable') ? 'non-nullable' : e.message); }",
        ),
      )

      // Nesting is unbounded: the transport does not cap container depth. 150 levels of
      // [ [ [ ... 1 ... ] ] ] survive the round trip.
      val deep = "(() => { let v = 1; for (let i = 0; i < 150; i++) v = [v]; return v; })()"
      assertEquals(
        "1",
        runtime.evaluateAsString(
          "(() => { let v = ExpoTestSupport.__convertRoundTrip($deep, [${CppType.ANY.code}]); " +
            "while (Array.isArray(v)) v = v[0]; return String(v); })()",
        ),
      )
    }
  }

  @Test
  fun `a dynamic slot declares nullability`() {
    HermesRuntime().use { runtime ->
      TestSupport.install(runtime)
      // `ANY` means `Any`: the engine rejects a null head and a null element, and carries one once
      // it is declared. `ANY?` is not a special case of the format either — the declaration decides
      // presence, because both sides read it; only bare tagged values (dynamic nesting) encode null
      // as a tag.
      val codes = mapOf(
        "ANY" to AnyType(TypeDescriptor.Simple(Any::class.java, false)).codes,
        "ANY?" to AnyType(TypeDescriptor.Simple(Any::class.java, true)).codes,
        "List<ANY>" to AnyType(
          TypeDescriptor.Parametrized(List::class.java, false, arrayOf(TypeDescriptor.Simple(Any::class.java, false))),
        ).codes,
        "List<ANY?>" to AnyType(
          TypeDescriptor.Parametrized(List::class.java, false, arrayOf(TypeDescriptor.Simple(Any::class.java, true))),
        ).codes,
      ).mapValues { (_, value) -> value.values.joinToString(",", "[", "]") }

      for ((declaration, value) in listOf("ANY" to "null", "List<ANY>" to "[1, null]")) {
        assertEquals(
          "threw:non-nullable",
          runtime.evaluateAsString(
            "try { ExpoTestSupport.__convertRoundTrip($value, ${codes[declaration]}); 'no'; } " +
              "catch (e) { 'threw:' + (e.message.includes('non-nullable') ? 'non-nullable' : e.message); }",
          ),
          declaration,
        )
      }
      assertEquals(
        "null",
        runtime.evaluateAsString(
          "JSON.stringify(ExpoTestSupport.__convertRoundTrip(null, ${codes["ANY?"]}))",
        ),
      )
      assertEquals(
        "[1,null]",
        runtime.evaluateAsString(
          "JSON.stringify(ExpoTestSupport.__convertRoundTrip([1, null], ${codes["List<ANY?>"]}))",
        ),
      )
      // Nulls nested inside a dynamic value are data, not a declaration: they always cross.
      assertEquals(
        """{"a":null}""",
        runtime.evaluateAsString(
          "JSON.stringify(ExpoTestSupport.__convertRoundTrip({a: null}, ${codes["ANY"]}))",
        ),
      )
    }
  }

  @Test
  fun `strings cross to Kotlin and back in both representations`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register("Echo", sharedEcho) {
        function(
          "labels",
          AnyType(
            TypeDescriptor.Parametrized(
              Map::class.java,
              false,
              arrayOf(
                TypeDescriptor.Simple(String::class.java, false),
                TypeDescriptor.Simple(String::class.java, true),
              ),
            ),
          ).buffered(),
          returns = AnyType(
            TypeDescriptor.Parametrized(
              Map::class.java,
              false,
              arrayOf(
                TypeDescriptor.Simple(String::class.java, false),
                TypeDescriptor.Simple(String::class.java, true),
              ),
            ),
          ).buffered(),
          methodName = "labels__trampoline",
        )
      }
      runtime.moduleRegistry.register("Big", sharedRecord) {
        function(
          "spread",
          AnyType(
            TypeDescriptor.Parametrized(
              List::class.java,
              false,
              arrayOf(TypeDescriptor.Simple(Point::class.java, false)),
            ),
          ).buffered(),
          returns = AnyType(
            TypeDescriptor.Parametrized(
              List::class.java,
              false,
              arrayOf(TypeDescriptor.Simple(Point::class.java, false)),
            ),
          ).buffered(),
          methodName = "spread__trampoline",
        )
      }

      // JS -> buffer -> Kotlin Map<String, String?> -> buffer -> JS, non-ASCII keys and values,
      // a lone surrogate, and an empty string.
      assertEquals(
        "true",
        runtime.evaluateAsString(
          """
          (() => {
            const input = {'żółć': 'café', ascii: 'plain', lone: 'a\uD800b', empty: ''};
            const out = expo.modules.Echo.labels(input);
            return Object.keys(out).length === 4 && out['żółć'] === 'café' &&
              out.ascii === 'plain' && out.empty === '' &&
              Array.from(out.lone, c => c.charCodeAt(0)).join() === '97,55296,98';
          })()
          """.trimIndent(),
        ),
      )

      // A non-ASCII record string field rides the buffer through RecordWriter/RecordReader.
      assertEquals(
        "true",
        runtime.evaluateAsString(
          "(() => { const out = expo.modules.Big.spread([{x: 1, y: 2, label: 'żółć'}]); return out[0].label === 'żółć'; })()",
        ),
      )

      // A >256 KiB non-ASCII payload overflows the fixed buffer and falls back to the
      // element-wise JNI path, which speaks genuine UTF-16 — content preserved either way.
      assertEquals(
        "true",
        runtime.evaluateAsString(
          """
          (() => {
            const label = 'ż'.repeat(200000);
            const out = expo.modules.Big.spread([{x: 1, y: 2, label}]);
            return out.length === 1 && out[0].label === label;
          })()
          """.trimIndent(),
        ),
      )
    }
  }

  @Test
  fun `binary payload decodes into cpp values and back`() {
    HermesRuntime().use { runtime ->
      TestSupport.install(runtime)
      // kind: 0=bool 1=int32 2=int64 3=float 4=double 5=string 6=vector<int32>
      //       7=vector<vector<int32>> 8=map<string,double> 9=vector<double>
      //       10=optional<double> 11=vector<uint8>
      fun roundTrip(expr: String, kind: Int): String =
        runtime.evaluateAsString(
          "JSON.stringify(ExpoTestSupport.__binaryNativeRoundTrip($expr, $kind))",
        )

      assertEquals("true", roundTrip("true", 0))
      assertEquals("42", roundTrip("42.9", 1)) // numeric coercion truncates, like fromJSIValue
      assertEquals("42", roundTrip("42", 2))
      assertEquals("3.5", roundTrip("3.5", 3))
      assertEquals("1.5e+300", roundTrip("1.5e300", 4))
      assertEquals("\"żółć\"", roundTrip("'żółć'", 5))
      // A lone surrogate survives the C++ std::string round trip (WTF-8 in the native codec) —
      // compare code units, JSON.stringify escapes it.
      assertEquals(
        "97,55357,98",
        runtime.evaluateAsString(
          "Array.from(ExpoTestSupport.__binaryNativeRoundTrip('a\\uD83Db', 5), c => c.charCodeAt(0)).join()",
        ),
      )
      assertEquals("[1,2,3]", roundTrip("[1.9, 2.9, 3]", 6))
      assertEquals("[[1],[2,3]]", roundTrip("[[1], [2, 3]]", 7))
      assertEquals("[]", roundTrip("[]", 9))
      assertEquals("[0.5,1.5]", roundTrip("[0.5, 1.5]", 9)) // exact match: memcpy both ways
      assertEquals("null", roundTrip("null", 10)) // optional<double> <- null
      assertEquals("2.5", roundTrip("2.5", 10))

      // Map iteration order isn't stable; assert membership instead of a fixed key order.
      assertEquals(
        "ok",
        runtime.evaluateAsString(
          """
          (() => {
            const out = ExpoTestSupport.__binaryNativeRoundTrip({a: 1, b: 2.5}, 8);
            return (out.a === 1 && out.b === 2.5 && Object.keys(out).length === 2) ? 'ok' : JSON.stringify(out);
          })()
          """.trimIndent(),
        ),
      )

      // vector<uint8_t> <-> ArrayBuffer.
      assertEquals(
        "1,2,255",
        runtime.evaluateAsString(
          "new Uint8Array(ExpoTestSupport.__binaryNativeRoundTrip(new Uint8Array([1, 2, 255]).buffer, 11)).join()",
        ),
      )

      // Shape mismatch throws, like the JSI converters.
      assertEquals(
        "threw",
        runtime.evaluateAsString(
          "try { ExpoTestSupport.__binaryNativeRoundTrip('nope', 6); 'no'; } catch (e) { 'threw'; }",
        ),
      )
    }
  }

  @Test
  fun `nullable declared types preserve nulls end to end`() {
    HermesRuntime().use { runtime ->
      val nullableDoubleList = TypeDescriptor.Parametrized(
        List::class.java,
        false,
        arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, true)),
      )
      val nullableStringMap = TypeDescriptor.Parametrized(
        Map::class.java,
        false,
        arrayOf(
          TypeDescriptor.Simple(String::class.java, false),
          TypeDescriptor.Simple(String::class.java, true),
        ),
      )
      runtime.moduleRegistry.register("Echo", sharedEcho) {
        function(
          "holes",
          AnyType(nullableDoubleList).buffered(),
          returns = AnyType(nullableDoubleList).buffered(),
          methodName = "holes__trampoline",
        )
        function(
          "labels",
          AnyType(nullableStringMap).buffered(),
          returns = AnyType(nullableStringMap).buffered(),
          methodName = "labels__trampoline",
        )
        function("sum", AnyType(
          TypeDescriptor.Parametrized(
            List::class.java,
            false,
            arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
          ),
        ).buffered(), returns = AnyType(TypeDescriptor.Double), methodName = "sum__trampoline")
      }

      fun runAll(): List<String> = listOf(
        runtime.evaluateAsString("JSON.stringify(expo.modules.Echo.holes([1.5, null, 2.5]))"),
        // Read the keys back individually: a Map's iteration order is not part of the contract,
        // so JSON.stringify would pin an ordering the bridge never promised.
        runtime.evaluateAsString(
          "(() => { const m = expo.modules.Echo.labels({a: 'x', b: null});" +
            "return m.a + '/' + m.b + '/' + Object.keys(m).length; })()",
        ),
        // A non-nullable List<Double> rejects null elements at the boundary.
        runtime.evaluateAsString(
          "try { expo.modules.Echo.sum([1.5, null]); 'no error'; } catch (e) { 'threw'; }",
        ),
      )

      val expected = listOf("[1.5,null,2.5]", "x/null/2", "threw")
      assertEquals(expected, runAll())
    }
  }

  @Test
  fun `registered methods dispatch through JNI slots and trampolines`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register("Echo", sharedEcho) {
        // List<Any?> is not buffer-safe: it crosses as a JNI object slot, no trampoline. The
        // elements are declared nullable — a dynamic element still has to say so.
        val dynamicList = AnyType(
          TypeDescriptor.Parametrized(List::class.java, false, arrayOf(TypeDescriptor.Simple(Any::class.java, true))),
        )
        function("identity", dynamicList, returns = dynamicList)
        function("sum", AnyType(
          TypeDescriptor.Parametrized(
            List::class.java,
            false,
            arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
          ),
        ).buffered(), returns = AnyType(TypeDescriptor.Double), methodName = "sum__trampoline")
        function("total", AnyType(
          TypeDescriptor.Parametrized(
            Map::class.java,
            false,
            arrayOf(
              TypeDescriptor.Simple(String::class.java, false),
              TypeDescriptor.Simple(Int::class.javaObjectType, false),
            ),
          ),
        ).buffered(), returns = AnyType(TypeDescriptor.Int), methodName = "total__trampoline")
      }

      fun runAll(): List<String> = listOf(
        runtime.evaluateAsString(
          "JSON.stringify(expo.modules.Echo.identity([1, 'two', null, [3], {four: 4}]))",
        ),
        runtime.evaluateAsString("expo.modules.Echo.sum([1.5, 2.5, 3])"),
        runtime.evaluateAsString("expo.modules.Echo.total({a: 1, b: 2})"),
      )

      val results = runAll()
      assertEquals("""[1,"two",null,[3],{"four":4}]""", results[0])
      assertEquals("7", results[1])
      assertEquals("3", results[2])
    }
  }

  @Test
  fun `primitive array signatures cross as bulk copies`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(PrimitiveArrayModule())

      fun runAll(): List<String> = listOf(
        runtime.evaluateAsString("JSON.stringify(expo.modules.Arr.echoDoubles([0.5, 1.5, 2.5]))"),
        runtime.evaluateAsString("JSON.stringify(expo.modules.Arr.echoInts([1.9, 2.2]))"),
        runtime.evaluateAsString("JSON.stringify(expo.modules.Arr.sum([1.5, 3]))"),
        runtime.evaluateAsString(
          "new Uint8Array(expo.modules.Arr.reverseBytes(new Uint8Array([1, 2, 3]).buffer)).join()",
        ),
        runtime.evaluateAsString("JSON.stringify(expo.modules.Arr.flags([true, false, true]))"),
        // No fixed-buffer limit: primitive arrays bulk-copy at any size.
        runtime.evaluateAsString("expo.modules.Arr.sum(Array.from({length: 100000}, () => 0.5))"),
      )

      val expected = listOf("[0.5,1.5,2.5]", "[1,2]", "4.5", "3,2,1", "[true,false,true]", "50000")
      assertEquals(expected, runAll())
    }
  }

  @Test
  fun `records cross as data classes`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.registerRecordFixture(module = sharedRecord)

      fun runAll(): List<String> = listOf(
        // JS object -> Point -> JS object; decoded properties come back in schema order.
        runtime.evaluateAsString(
          "JSON.stringify(expo.modules.Rec.move({x: 1.5, y: 2.5, label: 'a'}))",
        ),
        // Missing nullable field -> null; extra JS fields are ignored.
        runtime.evaluateAsString("expo.modules.Rec.label({x: 1, y: 2, extra: 'ignored'})"),
        // Records nested in lists, both directions.
        runtime.evaluateAsString(
          "JSON.stringify(expo.modules.Rec.spread([{x: 1, y: 2}, {x: 3, y: 4, label: 'b'}]))",
        ),
        // Return-only direction.
        runtime.evaluateAsString("JSON.stringify(expo.modules.Rec.make())"),
      )

      val expected = listOf(
        """{"x":2.5,"y":3.5,"label":"a"}""",
        "<none>",
        """[{"x":1,"y":2,"label":null},{"x":3,"y":4,"label":"b"}]""",
        """{"x":1.5,"y":2.5,"label":"made"}""",
      )
      assertEquals(expected, runAll())

      // A missing non-nullable field surfaces as an error naming the field.
      val error = runtime.evaluateAsString(
        "try { expo.modules.Rec.move({y: 2}); 'no error'; } catch (e) { String(e); }",
      )
      assertTrue("'x'" in error, "expected the error to name field 'x', got: $error")
    }
  }

  @Test
  fun `record property identifiers stay inside their Hermes runtime`() {
    fun exercise(runtime: HermesRuntime, moduleName: String): String {
      runtime.moduleRegistry.registerRecordFixture(moduleName)
      return runtime.evaluateAsString(
        """
        (() => {
          let point = {x: 1, y: 2, label: 'cached'};
          for (let i = 0; i < 1000; i++) point = expo.modules.$moduleName.move(point);
          return JSON.stringify(point);
        })()
        """.trimIndent(),
      )
    }

    HermesRuntime().use { first ->
      HermesRuntime().use { second ->
        assertEquals("""{"x":1001,"y":1002,"label":"cached"}""", exercise(first, "FirstRecord"))
        assertEquals("""{"x":1001,"y":1002,"label":"cached"}""", exercise(second, "SecondRecord"))
        // The thread-local cache clears when switching runtimes. Switching back must rebuild the
        // first runtime's plan rather than reuse identifiers owned by the second runtime.
        assertEquals(
          """{"x":1001,"y":1002,"label":"cached"}""",
          exercise(first, "FirstRecordAgain"),
        )
      }
    }

    // Recreate a runtime after both populated caches have been destroyed. A reused native address
    // must still build identifiers owned by the new Hermes runtime.
    HermesRuntime().use { recreated ->
      assertEquals(
        """{"x":1001,"y":1002,"label":"cached"}""",
        exercise(recreated, "RecreatedRecord"),
      )
    }
  }

  @Test
  fun `a schema pulled from native drives the round trip`() {
    // Nothing hands this schema to native. The first call that needs it makes native ask for it,
    // so the whole round trip below runs off the pulled copy.
    val schemaId = AnyType(TypeDescriptor.Simple(Pulled::class.java, false)).codes[1]
    val data = RecordRegistry.fetchSchema(schemaId)
    assertEquals("Pulled", data.name)
    assertEquals("Lio/github/expo/modules/v2/testapp/Pulled;", data.jniDescriptor)
    assertTrue(data.bufferSafe)
    assertEquals(listOf("n", "tag", "note"), data.fieldNames.toList())
    // No field here carries a Kotlin default, so native reads three `false`s.
    assertEquals(listOf(false, false, false), data.fieldOptional.toList())
    // One [codeCount, codes...] group per field: INT, STRING, then nullable STRING.
    assertEquals(
      listOf(
        1, CppType.INT.code,
        1, CppType.STRING.code,
        1, AnyType(TypeDescriptor.Simple(String::class.java, true)).codes[0],
      ),
      data.fieldTypes.toList(),
    )

    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(PulledModule())

      // Field order, types and nullability all come from the schema native parsed out of the
      // String[]/int[] pair — a marshalling error here shows up as a wrong or missing field.
      assertEquals(
        """{"n":8,"tag":"t","note":null}""",
        runtime.evaluateAsString("JSON.stringify(expo.modules.Pull.echo({n: 7, tag: 't'}))"),
      )
      assertEquals(
        """{"n":2,"tag":"u","note":"here"}""",
        runtime.evaluateAsString(
          "JSON.stringify(expo.modules.Pull.echo({n: 1, tag: 'u', note: 'here'}))",
        ),
      )

      // Native caches after the first miss, so repeated calls never fetch again.
      assertEquals(
        """{"n":3,"tag":"v","note":null}""",
        runtime.evaluateAsString("JSON.stringify(expo.modules.Pull.echo({n: 2, tag: 'v'}))"),
      )
    }
  }

  @Test
  fun `the widest record shape survives the pull transport`() {
    // 42 field names cross as a String[], each one a local ref and a toStdString on the C++ side.
    assertEquals(
      WIDE_FIELDS,
      RecordRegistry.fetchSchema(AnyType(TypeDescriptor.Simple(Wide::class.java, false)).codes[1]).fieldNames.size,
    )

    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(WideModule())

      val input = (0 until WIDE_FIELDS).joinToString(", ") { "field$it: $it" }
      val expected = (0 until WIDE_FIELDS).joinToString(",") { "\"field$it\":${it + 1}" }
      assertEquals(
        "{$expected}",
        runtime.evaluateAsString("JSON.stringify(expo.modules.Wide.echo({$input}))"),
      )
    }
  }

  @Test
  fun `a record returned through an ANY slot self-registers via its generated codec`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(DynamicRecordModule())
      // Sticker's codec is never named in any declaration: constructing the record (inside the
      // user method) runs the class init that registers it, and the dynamic conversion
      // decomposes it through the RecordRegistry.dynamicRecordToMap up-call.
      assertEquals(
        """{"kind":"sticker","value":{"id":7,"label":"seven"}}""",
        runtime.evaluateAsString("JSON.stringify(expo.modules.Dyn.make())"),
      )
    }
  }

  @Test
  fun `records inside an ANY result decompose without native schemas`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(LateRegistrationModule())
      // Rogue's class init runs mid-conversion, when the lazy list element is first read — the
      // object-slot path decomposes records with a Kotlin up-call and needs no native schema, so
      // even this late registration works on the first call.
      assertEquals(
        """[{"n":42}]""",
        runtime.evaluateAsString("JSON.stringify(expo.modules.Late.late())"),
      )
      // An unregistered class inside an ANY result fails with a descriptive error.
      val error = runtime.evaluateAsString(
        "try { expo.modules.Late.stranger(); 'no error'; } catch (e) { String(e); }",
      )
      assertTrue("RecordRegistry.register" in error, "expected an unregistered-class error, got: $error")
    }
  }

  @Test
  fun `oversized payloads fall back to object slots`() {
    HermesRuntime().use { runtime ->
      // Argument transport does not determine result transport: even inside the overflow branch,
      // a fitting nullable-list result is encoded into the shared buffer.
      Trampoline.prepareOverflowArguments()
      assertEquals(
        1,
        Trampoline.writeResult(
          null,
          TypeDescriptor.Parametrized(
            List::class.java,
            true,
            arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
          ),
        ),
      )

      runtime.moduleRegistry.register("Big", sharedRecord) {
        function(
          "spread",
          AnyType(
            TypeDescriptor.Parametrized(
              List::class.java,
              false,
              arrayOf(TypeDescriptor.Simple(Point::class.java, false)),
            ),
          ).buffered(),
          returns = AnyType(
            TypeDescriptor.Parametrized(
              List::class.java,
              false,
              arrayOf(TypeDescriptor.Simple(Point::class.java, false)),
            ),
          ).buffered(),
          methodName = "spread__trampoline",
        )
        function("explode", AnyType(TypeDescriptor.Int), returns = AnyType(
          TypeDescriptor.Parametrized(
            List::class.java,
            false,
            arrayOf(TypeDescriptor.Simple(Point::class.java, false)),
          ),
        ).buffered(), methodName = "explode__trampoline")
      }
      runtime.moduleRegistry.register("BigEcho", sharedEcho) {
        function(
          "sum",
          AnyType(
            TypeDescriptor.Parametrized(
              List::class.java,
              false,
              arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
            ),
          ).buffered(),
          returns = AnyType(TypeDescriptor.Double),
          methodName = "sum__trampoline",
        )
      }

      // Argument overflow: ~20k labeled points exceed the 256 KiB buffer. The explicit overflow
      // branch reads them from the fixed object slots, where records cross as Maps and rebuild
      // via fromMap.
      assertEquals(
        "true",
        runtime.evaluateAsString(
          """
          (() => {
            const points = Array.from({length: 20000}, (_, i) => ({x: i, y: i * 2, label: 'p' + i}));
            const out = expo.modules.Big.spread(points);
            return out.length === 20000 && out[0].x === 0 && out[19999].label === 'p19999';
          })()
          """.trimIndent(),
        ),
      )
      // A small payload keeps riding the buffer through the same declaration.
      assertEquals(
        "true",
        runtime.evaluateAsString(
          "(() => { const out = expo.modules.Big.spread([{x: 1, y: 2}]); return out.length === 1 && out[0].label === null; })()",
        ),
      )

      // A primitive-list payload overflows the same way, no records involved.
      assertEquals(
        "20000",
        runtime.evaluateAsString("expo.modules.BigEcho.sum(Array.from({length: 40000}, () => 0.5))"),
      )

      // Result overflow needs no extra method: writeResult stashes the object shape out-of-band
      // and returns the sentinel; the bridge fetches and converts it element-wise.
      assertEquals(
        "true",
        runtime.evaluateAsString(
          "(() => { const out = expo.modules.Big.explode(20000); return out.length === 20000 && out[19999].x === 19999 && out[0].label === null; })()",
        ),
      )
      // Small results still ride the buffer.
      assertEquals("true", runtime.evaluateAsString("expo.modules.Big.explode(2).length === 2"))

    }
  }

  @Test
  fun `a record with JSI-handle fields crosses as a live Map slot`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register("HR", sharedHandleRecord) {
        function(
          "attach",
          AnyType(TypeDescriptor.Simple(Listener::class.java, false)),
          returns = AnyType(TypeDescriptor.Simple(Listener::class.java, false)),
          methodName = "attach__trampoline",
        )
        function(
          "extraKind",
          AnyType(TypeDescriptor.Simple(Listener::class.java, false)),
          returns = AnyType(TypeDescriptor.Simple(String::class.java, false)),
          methodName = "extraKind__trampoline",
        )
      }

      // The handle field is a live reference: Kotlin's mutation lands on the SAME JS object, and
      // the returned record's `target` is identical (===) to what JS passed in.
      assertEquals(
        "true",
        runtime.evaluateAsString(
          """
          (() => {
            const t = {};
            const out = expo.modules.HR.attach({name: 'n', target: t, extra: 5});
            return out.target === t && t.attached === true && out.name === 'n' &&
              out.extra === 5 && out.backup === null;
          })()
          """.trimIndent(),
        ),
      )

      // A JS_VALUE field wraps whatever is there — a missing property arrives as undefined.
      assertEquals(
        "UNDEFINED",
        runtime.evaluateAsString("expo.modules.HR.extraKind({name: 'x', target: {}})"),
      )
      // The nullable JS_OBJECT field accepts a real object too.
      assertEquals(
        "true",
        runtime.evaluateAsString(
          "var b = {}; expo.modules.HR.attach({name: 'x', target: {}, backup: b}).backup === b",
        ),
      )

      // A missing non-nullable handle field surfaces as an error naming the field.
      val missing = runtime.evaluateAsString(
        "try { expo.modules.HR.attach({name: 'x'}); 'no error'; } catch (e) { String(e); }",
      )
      assertTrue("'target'" in missing, "expected the error to name field 'target', got: $missing")
      // A non-object where a JS_OBJECT field is declared is a type error.
      assertEquals(
        "threw",
        runtime.evaluateAsString(
          "try { expo.modules.HR.attach({name: 'x', target: 42}); 'no'; } catch (e) { 'threw'; }",
        ),
      )
    }
  }

  @Test
  fun `payload args and object slots mix in one trampoline signature`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register("HR", sharedHandleRecord) {
        function(
          "mixed",
          AnyType(TypeDescriptor.Simple(String::class.java, false)),
          AnyType(TypeDescriptor.Simple(MyRecord::class.java, false)).buffered(),
          AnyType(TypeDescriptor.Simple(Listener::class.java, false)),
          AnyType(
            TypeDescriptor.Parametrized(
              List::class.java,
              false,
              arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
            ),
          ).buffered(),
          AnyType(TypeDescriptor.Simple(Any::class.java, false)),
          returns = AnyType(TypeDescriptor.Simple(Listener::class.java, false)),
          methodName = "mixed__trampoline",
        )
        function(
          "first",
          AnyType(
            TypeDescriptor.Parametrized(
              List::class.java,
              false,
              arrayOf(TypeDescriptor.Simple(Listener::class.java, false)),
            ),
          ),
          returns = AnyType(TypeDescriptor.Simple(Listener::class.java, false)),
          methodName = "first__trampoline",
        )
      }

      // `rec` and `xs` ride the payload; `flagged` and `dyn` are JNI object slots — positional
      // payload decode must line up even though the two groups interleave in the declaration.
      assertEquals(
        "true",
        runtime.evaluateAsString(
          """
          (() => {
            const t = {};
            const out = expo.modules.HR.mixed(
              'p-', {x: 1, b: 'rec'}, {name: 'l', target: t, extra: null}, [1.5, 2.5], 'dyn');
            return out.target === t && t.sum === 4 && out.name === 'p-recdyn';
          })()
          """.trimIndent(),
        ),
      )

      // Overflow keeps each payload value at its original declared position: rec is slot 1 and
      // xs is slot 3 even though the trampoline's JNI signature omits both.
      assertEquals(
        "true",
        runtime.evaluateAsString(
          """
          (() => {
            const t = {};
            const xs = Array.from({length: 40000}, () => 0.5);
            const out = expo.modules.HR.mixed(
              'p-', {x: 1, b: 'rec'}, {name: 'l', target: t, extra: null}, xs, 'dyn');
            return out.target === t && t.sum === 20000 && out.name === 'p-recdyn';
          })()
          """.trimIndent(),
        ),
      )

      // A list of flagged records crosses as a JNI List of Maps, handles still live.
      assertEquals(
        "true",
        runtime.evaluateAsString(
          """
          (() => {
            const a = {}, b = {};
            const out = expo.modules.HR.first([
              {name: 'a', target: a, extra: 1}, {name: 'b', target: b, extra: 2}]);
            return out.target === a && out.name === 'a';
          })()
          """.trimIndent(),
        ),
      )
    }
  }

  @Test
  fun `Any values carry live handles and structures without a trampoline`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(DynamicHandleModule())

      // A handle nested inside a returned Kotlin Map unwraps to the identical JS object.
      assertEquals(
        "true",
        runtime.evaluateAsString(
          "var o = {v: 1}; var r = expo.modules.DynH.wrap(o); r.original === o && r.n === 1",
        ),
      )

      // Nested structures survive the element-wise object path in both directions.
      assertEquals(
        """{"a":1,"b":["x",null],"c":{"d":true}}""",
        runtime.evaluateAsString(
          "JSON.stringify(expo.modules.DynH.passthrough({a: 1, b: ['x', null], c: {d: true}}))",
        ),
      )

      // An ArrayBuffer inside an Any crosses as a ByteArray and comes back as an ArrayBuffer.
      assertEquals(
        "1,2,255",
        runtime.evaluateAsString(
          "new Uint8Array(expo.modules.DynH.passthrough(new Uint8Array([1, 2, 255]).buffer)).join()",
        ),
      )

      // A JS function inside an Any is still unsupported and throws.
      val fn = runtime.evaluateAsString(
        "try { expo.modules.DynH.passthrough({fn: () => 1}); 'no error'; } catch (e) { String(e); }",
      )
      assertTrue("function" in fn, "expected the JS-function error, got: $fn")
    }
  }

  @Test
  fun `JS_VALUE and JS_OBJECT slots pass live JSI handles`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(JsHandleModule())

      // A JS_VALUE handle wraps whatever arrives, null included. A missing argument is not a
      // value: the declared arity is checked before any slot is encoded, so the call throws.
      assertEquals("NUMBER", runtime.evaluateAsString("expo.modules.Js.kindOf(42)"))
      assertEquals("NULL", runtime.evaluateAsString("expo.modules.Js.kindOf(null)"))
      assertEquals(
        "threw",
        runtime.evaluateAsString(
          "try { expo.modules.Js.kindOf(); 'no error'; } catch (e) { 'threw'; }",
        ),
      )

      // A returned handle unwraps to the SAME JS value — reference identity, not a copy.
      assertEquals(
        "true",
        runtime.evaluateAsString("var o = {a: 1}; expo.modules.Js.echo(o) === o"),
      )

      // A JS_OBJECT handle is a live reference: mutations made in Kotlin are visible in JS.
      assertEquals(
        "true",
        runtime.evaluateAsString(
          "var p = {x: 1}; expo.modules.Js.stamp(p) === p && p.stamped === true",
        ),
      )
      assertEquals(
        "alpha",
        runtime.evaluateAsString("expo.modules.Js.firstName({alpha: 1, beta: 2})"),
      )

      // A non-object where a JS_OBJECT is declared is a type error, surfaced as a JS error.
      assertEquals(
        "threw",
        runtime.evaluateAsString(
          "try { expo.modules.Js.firstName(42); 'no error'; } catch (e) { 'threw'; }",
        ),
      )

      // Both array accessors must retain every native-created element after its temporary JNI
      // local reference is released.
      runtime.evaluate("globalThis.handleArray = [10, 20, 30]")
      val arrayValue = runtime.global().getProperty("handleArray")
      assertEquals(listOf(10, 20, 30), arrayValue.getArray().map { it.getInt() })
      assertEquals(listOf(10, 20, 30), arrayValue.getObject().getArray().map { it.getInt() })
    }
  }

  @Test
  fun `unsetProperty writes undefined and keeps the key`() {
    HermesRuntime().use { runtime ->
      val target = runtime.evaluate("globalThis.target = {kept: 1, dropped: 2}").getObject()

      target.unsetProperty("dropped")
      // This is an assignment, not a delete: the value is undefined, the key survives.
      assertEquals(JavaScriptValue.Kind.UNDEFINED, target.getProperty("dropped").kind())
      assertTrue(target.hasProperty("dropped"))
      assertEquals("true", runtime.evaluateAsString("'dropped' in globalThis.target"))

      // An untyped null routes to unsetProperty; a typed null still writes JavaScript null.
      target["kept"] = null
      assertEquals(JavaScriptValue.Kind.UNDEFINED, target.getProperty("kept").kind())
      target["kept"] = null as String?
      assertEquals(JavaScriptValue.Kind.NULL, target.getProperty("kept").kind())
    }
  }

  @Test
  fun `unbuffered typed containers cross as JNI object slots`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register("Direct", sharedEcho) {
        // No buffered(), no trampoline: the typed list crosses as a java.util.List slot with
        // recursive element coercion, and the user method is invoked directly.
        function(
          "sum",
          AnyType(
            TypeDescriptor.Parametrized(
              List::class.java,
              false,
              arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
            ),
          ),
          returns = AnyType(TypeDescriptor.Double),
        )
      }

      assertEquals("7", runtime.evaluateAsString("expo.modules.Direct.sum([1.5, 2.5, 3])"))
      // Element coercion still validates: null is not a Double.
      assertEquals(
        "threw",
        runtime.evaluateAsString(
          "try { expo.modules.Direct.sum([1.5, null]); 'no error'; } catch (e) { 'threw'; }",
        ),
      )
    }
  }

  @Test
  fun `buffered leaf values ride the trampoline payload`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register("Leaf", sharedBufferedLeaf) {
        function(
          "shout",
          AnyType(TypeDescriptor.Simple(String::class.java, false)).buffered(),
          returns = AnyType(TypeDescriptor.Simple(String::class.java, false)).buffered(),
          methodName = "shout__trampoline",
        )
        function(
          "bump",
          AnyType(TypeDescriptor.Simple(Int::class.javaObjectType, true)).buffered(),
          returns = AnyType(TypeDescriptor.Simple(Int::class.javaObjectType, true)).buffered(),
          methodName = "bump__trampoline",
        )
        function(
          "addOne",
          AnyType(TypeDescriptor.IntArray(false)).buffered(),
          returns = AnyType(TypeDescriptor.IntArray(false)).buffered(),
          methodName = "addOne__trampoline",
        )
        function(
          "reverseBytes",
          AnyType(TypeDescriptor.ByteArray(false)).buffered(),
          returns = AnyType(TypeDescriptor.ByteArray(false)).buffered(),
          methodName = "reverseBytes__trampoline",
        )
        function(
          "label",
          AnyType(TypeDescriptor.Simple(String::class.java, false)),
          AnyType(
            TypeDescriptor.Parametrized(
              List::class.java,
              false,
              arrayOf(TypeDescriptor.Simple(Double::class.javaObjectType, false)),
            ),
          ).buffered(),
          AnyType(TypeDescriptor.Int),
          AnyType(TypeDescriptor.Simple(String::class.java, false)).buffered(),
          returns = AnyType(TypeDescriptor.Simple(String::class.java, false)).buffered(),
          methodName = "label__trampoline",
        )
        function(
          "visit",
          urlType.buffered(),
          returns = AnyType(TypeDescriptor.Simple(String::class.java, false)),
          methodName = "visit__trampoline",
        )
      }

      fun runAll(): List<String> = listOf(
        // ASCII and UTF-16 payloads exercise both branches of the adaptive string buffer format.
        runtime.evaluateAsString("expo.modules.Leaf.shout('hey')"),
        runtime.evaluateAsString("expo.modules.Leaf.shout('héj ⚡')"),
        // A nullable buffered boxed scalar (0x300 head) round-trips null and non-null.
        runtime.evaluateAsString("String(expo.modules.Leaf.bump(7))"),
        runtime.evaluateAsString("String(expo.modules.Leaf.bump(null))"),
        runtime.evaluateAsString("JSON.stringify(expo.modules.Leaf.addOne([1, 2, 3]))"),
        runtime.evaluateAsString(
          "new Uint8Array(expo.modules.Leaf.reverseBytes(new Uint8Array([1, 2, 3]).buffer)).join()",
        ),
        // Mixed transports: JNI slots and payload values interleave in one signature.
        runtime.evaluateAsString("expo.modules.Leaf.label('p', [1.5, 4.5], 9, 'tail')"),
        // A converter whose String bridge rides the payload.
        runtime.evaluateAsString("expo.modules.Leaf.visit('https://expo.dev/home')"),
      )

      val expected = listOf("HEY", "HÉJ ⚡", "8", "null", "[2,3,4]", "3,2,1", "p|6.0|9|tail", "expo.dev")
      assertEquals(expected, runAll())
    }
  }

  @Test
  fun `an oversized buffered string falls back to overflow slots in both directions`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register("BigLeaf", sharedBufferedLeaf) {
        function(
          "shout",
          AnyType(TypeDescriptor.Simple(String::class.java, false)).buffered(),
          returns = AnyType(TypeDescriptor.Simple(String::class.java, false)).buffered(),
          methodName = "shout__trampoline",
        )
      }

      // 300k ASCII chars exceed the fixed bridge buffer: the argument takes the overflow object
      // slot and the result overflows out-of-band; the value still round-trips intact.
      assertEquals(
        "true",
        runtime.evaluateAsString(
          """
          (() => {
            const s = 'ab'.repeat(150 * 1024);
            const out = expo.modules.BigLeaf.shout(s);
            return out.length === s.length && out.startsWith('ABAB') && out.endsWith('AB');
          })()
          """.trimIndent(),
        ),
      )
      // A small payload keeps riding the buffer through the same declaration.
      assertEquals("OK", runtime.evaluateAsString("expo.modules.BigLeaf.shout('ok')"))
    }
  }

  @Test
  fun `a buffered leaf property round-trips through its trampoline accessors`() {
    HermesRuntime().use { runtime ->
      val settings = PropertyModule()
      runtime.moduleRegistry.register("LeafProps", settings) {
        property(
          "motto",
          AnyType(TypeDescriptor.Simple(String::class.java, false)).buffered(),
          mutable = true,
          propertyName = "motto__trampoline",
        )
      }

      assertEquals("carpe diem", runtime.evaluateAsString("expo.modules.LeafProps.motto"))
      runtime.evaluate("expo.modules.LeafProps.motto = 'tempus fugit'")
      assertEquals("tempus fugit", settings.motto)
      assertEquals("tempus fugit", runtime.evaluateAsString("expo.modules.LeafProps.motto"))
    }
  }

  @Test
  fun `a buffer-safe record without buffered() Map-rides through its trampoline`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register("MapRide", sharedTrampoline) {
        function("mapRide", AnyType(TypeDescriptor.Simple(MyRecord::class.java, false)),
          returns = AnyType(TypeDescriptor.Simple(MyRecord::class.java, false)), methodName = "mapRide__trampoline")
      }

      assertEquals(
        """{"x":8,"b":"seven!"}""",
        runtime.evaluateAsString(
          "JSON.stringify(expo.modules.MapRide.mapRide({x: 7, b: 'seven'}))",
        ),
      )
    }
  }

  @Test
  fun `optional record fields take their Kotlin defaults on both crossing shapes`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(DefaultsModule())

      for (name in listOf("describe", "describeMap")) {
        val call = { arg: String -> runtime.evaluateAsString("expo.modules.Defaults.$name($arg)") }

        // Every field present.
        assertEquals("t/1/l/2", call("{tag: 't', count: 1, label: 'l', trailing: 2}"), name)
        // All three optional fields missing: the Kotlin defaults apply.
        assertEquals("t/5/z/7", call("{tag: 't'}"), name)
        // `undefined` is the same as missing.
        assertEquals(
          "t/5/z/7",
          call("{tag: 't', count: undefined, label: undefined, trailing: undefined}"),
          name,
        )
        // Explicit null on a nullable optional field is NOT the default.
        assertEquals("t/5/null/7", call("{tag: 't', label: null}"), name)
        // An absent field in the middle must not shift the one that follows it.
        assertEquals("t/5/z/3", call("{tag: 't', trailing: 3}"), name)
      }

      // Explicit null on a NON-nullable field is still the declared-non-nullable error, default
      // or no default.
      val error = assertFailsWith<Exception> {
        runtime.evaluateAsString("expo.modules.Defaults.describe({tag: 't', count: null})")
      }.message ?: ""
      assertTrue("non-nullable" in error, "expected the non-nullable error, got: $error")
      assertTrue("'count'" in error, "expected the error to name field 'count', got: $error")
    }
  }

  @Test
  fun `records cross via hardcoded trampolines`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register("Tramp", sharedTrampoline) {
        function("record",
          AnyType(TypeDescriptor.Simple(MyRecord::class.java, false)).buffered(), returns = AnyType(
            TypeDescriptor.Simple(MyRecord::class.java, false),
          ).buffered(), methodName = "record__trampoline")
        function(
          "tag",
          AnyType(TypeDescriptor.Simple(String::class.java, false)),
          AnyType(TypeDescriptor.Simple(MyRecord::class.java, false)).buffered(),
          AnyType(TypeDescriptor.Int),
          returns = AnyType(TypeDescriptor.Simple(String::class.java, false)),
          methodName = "tag__trampoline",
        )
        function("combine",
          AnyType(TypeDescriptor.Simple(MyRecord::class.java, false)).buffered(),
          AnyType(TypeDescriptor.Simple(Tagged::class.java, false)).buffered(), returns = AnyType(
            TypeDescriptor.Simple(MyRecord::class.java, false),
          ).buffered(), methodName = "combine__trampoline")
        function("make", AnyType(TypeDescriptor.Int), returns = AnyType(
          TypeDescriptor.Simple(MyRecord::class.java, false),
        ).buffered(), methodName = "make__trampoline")
        function("boom",
          AnyType(TypeDescriptor.Simple(MyRecord::class.java, false)).buffered(), returns = AnyType(
            TypeDescriptor.Simple(MyRecord::class.java, false),
          ).buffered(), methodName = "boom__trampoline")
      }

      fun runAll(): List<String> = listOf(
        // Record arg + record return, both through the shared buffer in one JNI call.
        runtime.evaluateAsString("JSON.stringify(expo.modules.Tramp.record({x: 7, b: 'seven'}))"),
        // Record arg mixed with primitives, which stay ordinary JNI parameters.
        runtime.evaluateAsString("expo.modules.Tramp.tag('p-', {x: 5, b: 'z'}, 2)"),
        // Two record args: concatenated payloads, read back positionally.
        runtime.evaluateAsString(
          "JSON.stringify(expo.modules.Tramp.combine({x: 1, b: 'a'}, {v: 2, note: 'n'}))",
        ),
        // Nullable field absent in the second record.
        runtime.evaluateAsString(
          "JSON.stringify(expo.modules.Tramp.combine({x: 1, b: 'a'}, {v: 2}))",
        ),
        // Record-return-only: the trampoline still takes (and ignores) a zero payload length.
        runtime.evaluateAsString("JSON.stringify(expo.modules.Tramp.make(9))"),
      )

      val expected = listOf(
        """{"x":7,"b":"seven"}""",
        "p-zz5",
        """{"x":3,"b":"an"}""",
        """{"x":3,"b":"a"}""",
        """{"x":9,"b":"made"}""",
      )
      assertEquals(expected, runAll())

      // A Kotlin exception thrown by the user method crosses back as a JS error.
      val thrown = runtime.evaluateAsString(
        "try { expo.modules.Tramp.boom({x: 1, b: 'bad'}); 'no error'; } catch (e) { String(e); }",
      )
      assertTrue("native call failed" in thrown, "expected a wrapped native error, got: $thrown")
      assertTrue("boom: bad" in thrown, "expected the Kotlin message, got: $thrown")

      // A missing non-nullable field surfaces as an error naming the field.
      val missingField = runtime.evaluateAsString(
        "try { expo.modules.Tramp.record({b: 'x'}); 'no error'; } catch (e) { String(e); }",
      )
      assertTrue("'x'" in missingField, "expected the error to name field 'x', got: $missingField")

      // A declared-but-missing trampoline fails that function's first call, naming the method.
      runtime.moduleRegistry.register("Broken", sharedTrampoline) {
        function("record",
          AnyType(TypeDescriptor.Simple(MyRecord::class.java, false)).buffered(), returns = AnyType(
            TypeDescriptor.Simple(MyRecord::class.java, false),
          ).buffered(), methodName = "missing__trampoline")
      }
      val missingTrampoline = runtime.evaluateAsString(
        "try { expo.modules.Broken.record({x: 1, b: 'a'}); 'no error'; } catch (e) { String(e); }",
      )
      assertTrue(
        "missing__trampoline" in missingTrampoline,
        "expected the error to name the trampoline, got: $missingTrampoline",
      )
    }
  }

  @Test
  fun `converted types cross via trampolines`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(ConvertedModule())

      fun runAll(): List<String> = listOf(
        // JNI-slot String bridge: JS string -> trampoline String -> URL.
        runtime.evaluateAsString("expo.modules.Conv.host('https://expo.dev/path')"),
        // Converted return with a String bridge: the trampoline returns toBridge(...) directly.
        runtime.evaluateAsString("expo.modules.Conv.home()"),
        // Two Duration args and a Duration result, all as plain JS numbers (seconds).
        runtime.evaluateAsString("expo.modules.Conv.total(1500, 250)"),
        // Nullable converted arg with an object bridge: JS null crosses as a Java null.
        runtime.evaluateAsString("expo.modules.Conv.describe('https://expo.dev/x')"),
        runtime.evaluateAsString("expo.modules.Conv.describe(null)"),
        // Record with converted fields (STRING/DOUBLE bridges inside the payload).
        runtime.evaluateAsString(
          "JSON.stringify(expo.modules.Conv.extend({url: 'https://expo.dev/', ttl: 100}, 50))",
        ),
        // Missing nullable converted field decodes as null.
        runtime.evaluateAsString(
          "JSON.stringify(expo.modules.Conv.extend({url: 'https://expo.dev/'}, 50))",
        ),
      )

      val expected = listOf(
        "expo.dev",
        "https://expo.dev/home",
        "1750",
        "expo.dev",
        "none",
        """{"url":"https://expo.dev/","ttl":150}""",
        """{"url":"https://expo.dev/","ttl":50}""",
      )
      assertEquals(expected, runAll())

      // A fromBridge failure (malformed URL) surfaces as a JS error.
      val invalid = runtime.evaluateAsString(
        "try { expo.modules.Conv.host('::not a url::'); 'no error'; } catch (e) { String(e); }",
      )
      assertTrue("no error" !in invalid, "expected a JS error for a malformed URL, got: $invalid")
    }
  }

  @Test
  fun `exports Kotlin val and var properties as live JavaScript accessors`() {
    HermesRuntime().use { runtime ->
      val settings = PropertyModule()
      runtime.moduleRegistry.register("Properties", settings) {
        property("version", AnyType(TypeDescriptor.Simple(String::class.java, false)))
        property("count", AnyType(TypeDescriptor.Int), mutable = true)
        property("ready", AnyType(TypeDescriptor.Bool), propertyName = "isReady")
      }

      assertEquals(
        "1.0:1:true",
        runtime.evaluateAsString(
          "(() => { const p = expo.modules.Properties; " +
            "return p.version + ':' + p.count + ':' + p.ready; })()",
        ),
      )
      assertEquals(
        "7",
        runtime.evaluateAsString(
          "expo.modules.Properties.count = 7; expo.modules.Properties.count",
        ),
      )
      assertEquals(7, settings.count)
      assertEquals(
        "true",
        runtime.evaluateAsString(
          "Object.keys(expo.modules.Properties).sort().join() === 'count,ready,version'",
        ),
      )
      assertEquals(
        "read-only:1.0",
        runtime.evaluateAsString(
          "(() => { 'use strict'; const p = expo.modules.Properties; " +
            "try { p.version = '2.0'; return 'writable'; } " +
            "catch (_) { return 'read-only:' + p.version; } })()",
        ),
      )
    }
  }

  @Test
  fun `property trampolines convert values and fall back when a setter payload overflows`() {
    HermesRuntime().use { runtime ->
      val settings = PropertyModule()
      runtime.moduleRegistry.register("TrampolineProperties", settings) {
        property(
          "homepage",
          urlType,
          mutable = true,
          propertyName = "homepage__trampoline",
        )
        property(
          "values",
          AnyType(
            TypeDescriptor.Parametrized(
              List::class.java,
              false,
              arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
            ),
          ).buffered(),
          mutable = true,
          propertyName = "values__trampoline",
        )
      }

      assertEquals(
        "https://example.com/path|3,4,5",
        runtime.evaluateAsString(
          "(() => { const p = expo.modules.TrampolineProperties; " +
            "p.homepage = 'https://example.com/path'; p.values = [3, 4, 5]; " +
            "return p.homepage + '|' + p.values.join(','); })()",
        ),
      )
      assertEquals("example.com", settings.homepage.host)
      assertEquals(listOf(3, 4, 5), settings.values)

      // 70k Ints exceed the 256 KiB fixed buffer. The normal setter trampoline reads the fixed
      // object slot; reading the same value uses Trampoline.writeResult's result fallback.
      assertEquals(
        "70000:69999",
        runtime.evaluateAsString(
          "(() => { const p = expo.modules.TrampolineProperties; " +
            "p.values = Array.from({length: 70000}, (_, i) => i); " +
            "return p.values.length + ':' + p.values[69999]; })()",
        ),
      )
    }
  }

  @Test
  fun `generated property accessors are live JavaScript accessors`() {
    HermesRuntime().use { runtime ->
      val props = GeneratedPropertyModule()
      runtime.moduleRegistry.register(props)

      // A `val` is read-only from JS; a `var` is writable and stays live in both directions.
      assertEquals(
        "1.0|1|true",
        runtime.evaluateAsString(
          "(() => { const p = expo.modules.GenProps; " +
            "return [p.version, p.count, p.isReady].join('|'); })()",
        ),
      )
      assertEquals(
        "read-only:1.0",
        runtime.evaluateAsString(
          "(() => { 'use strict'; const p = expo.modules.GenProps; " +
            "try { p.version = '2.0'; return 'writable'; } " +
            "catch (_) { return 'read-only:' + p.version; } })()",
        ),
      )

      // Every writable shape: a direct slot, a String written on the payload and read back out of a
      // slot, a converted value, a buffered container, and a buffered boxed scalar.
      assertEquals(
        "7|hello|example.com|3,4,5|9",
        runtime.evaluateAsString(
          "(() => { const p = expo.modules.GenProps; " +
            "p.count = 7; p.motto = 'hello'; p.homepage = 'https://example.com/path'; " +
            "p.values = [3, 4, 5]; p.nullableCount = 9; " +
            "return [p.count, p.motto, p.homepage.replace(/^https:\\/\\//, '').split('/')[0], " +
            "p.values.join(','), p.nullableCount].join('|'); })()",
        ),
      )
      // The setters really wrote through to Kotlin, rather than JS caching its own values.
      assertEquals(7, props.count)
      assertEquals("hello", props.motto)
      assertEquals("example.com", props.homepage.host)
      assertEquals(listOf(3, 4, 5), props.values)
      assertEquals(9, props.nullableCount)

      // A nullable boxed property carries null both ways.
      assertEquals(
        "object:null",
        runtime.evaluateAsString(
          "(() => { const p = expo.modules.GenProps; p.nullableCount = null; " +
            "return typeof p.nullableCount + ':' + p.nullableCount; })()",
        ),
      )
      assertNull(props.nullableCount)
    }
  }

  @Test
  fun `a generated buffered property falls back to slots when its payload overflows`() {
    HermesRuntime().use { runtime ->
      val props = GeneratedPropertyModule()
      runtime.moduleRegistry.register(props)

      // 70k Ints exceed the 256 KiB fixed buffer, so the setter reads the fixed object slot and the
      // getter's writeResult stashes its result out of band.
      assertEquals(
        "70000:69999",
        runtime.evaluateAsString(
          "(() => { const p = expo.modules.GenProps; " +
            "p.values = Array.from({length: 70000}, (_, i) => i); " +
            "return p.values.length + ':' + p.values[69999]; })()",
        ),
      )
      assertEquals(70000, props.values.size)
    }
  }

  @Test
  fun `register(module) rejects a class that declares no exports`() {
    HermesRuntime().use { runtime ->
      // Without @JS there is no generated definition, so there is nothing to register.
      val error = assertFailsWith<IllegalArgumentException> {
        runtime.moduleRegistry.register(UnannotatedModule())
      }
      assertTrue(error.message!!.contains("@JS"), "unhelpful message: ${error.message}")

      // The generated path enforces the same one-name-once rule as the DSL.
      runtime.moduleRegistry.register(GeneratedPropertyModule())
      assertFailsWith<IllegalArgumentException> {
        runtime.moduleRegistry.register(GeneratedPropertyModule())
      }
    }
  }

  @Test
  fun `generated trampolines read every buffered leaf back off the payload`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(GeneratedLeafModule())

      // A boxed scalar carries its value and its null through the presence byte.
      assertEquals(
        "7|null|9|null|1.5|null|2.5|null|true|null",
        runtime.evaluateAsString(
          "(() => { const m = expo.modules.GenLeaves; return [" +
            "m.echoInt(7), m.echoInt(null), m.echoLong(9), m.echoLong(null), " +
            "m.echoFloat(1.5), m.echoFloat(null), m.echoDouble(2.5), m.echoDouble(null), " +
            "m.echoBoolean(true), m.echoBoolean(null)].map(String).join('|'); })()",
        ),
      )

      // A nullable String uses the nextStringOrNull reader.
      assertEquals(
        "hi|null|<empty>",
        runtime.evaluateAsString(
          "(() => { const m = expo.modules.GenLeaves; " +
            "const show = v => v === null ? 'null' : (v === '' ? '<empty>' : v); return [" +
            "m.echoNullableString('hi'), m.echoNullableString(null), " +
            "m.echoNullableString('')].map(show).join('|'); })()",
        ),
      )

      // Buffer.YES moves a primitive array onto the payload, in both directions.
      assertEquals(
        "10|0.5,1.5,2.5",
        runtime.evaluateAsString(
          "(() => { const m = expo.modules.GenLeaves; return [" +
            "m.sumBuffered([1, 2, 3, 4]), m.echoBufferedDoubles([0.5, 1.5, 2.5]).join(',')" +
            "].join('|'); })()",
        ),
      )

      // Eight payload arguments keep their declared order.
      assertEquals(
        "abcdefgh",
        runtime.evaluateAsString(
          "expo.modules.GenLeaves.join('a', 'b', 'c', 'd', 'e', 'f', 'g', 'h')",
        ),
      )
    }
  }

  @Test
  fun `a throwing generated trampoline leaves the shared buffer usable`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(GeneratedLeafModule())

      // The payload is read, then the user method throws. `args.finish()` still has to run, and the
      // next call has to find the buffer in a clean state.
      assertEquals(
        "threw|abcdefgh|42",
        runtime.evaluateAsString(
          "(() => { const m = expo.modules.GenLeaves; " +
            "let first; try { m.boom('x'); first = 'no throw'; } catch (_) { first = 'threw'; } " +
            "return [first, m.join('a','b','c','d','e','f','g','h'), " +
            "m.sumBuffered([40, 2])].join('|'); })()",
        ),
      )
    }
  }

  @Test
  fun `a nullable record crosses through a generated trampoline`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(GeneratedNullableRecordModule())

      assertEquals(
        """{"id":3,"label":"three"}|null""",
        runtime.evaluateAsString(
          "(() => { const m = expo.modules.GenNullableRecord; return [" +
            "JSON.stringify(m.echo({id: 3, label: 'three'})), " +
            "JSON.stringify(m.echo(null))].join('|'); })()",
        ),
      )

      // Absent and explicit null both reach Kotlin as null, and the record still decodes when present.
      assertEquals(
        "3/three|none|none",
        runtime.evaluateAsString(
          "(() => { const m = expo.modules.GenNullableRecord; return [" +
            "m.describe({id: 3, label: 'three'}), m.describe(null), m.describe(undefined)" +
            "].join('|'); })()",
        ),
      )

      // The return direction: a null record result and a present one.
      assertEquals(
        """{"id":7,"label":"seven"}|null""",
        runtime.evaluateAsString(
          "(() => { const m = expo.modules.GenNullableRecord; return [" +
            "JSON.stringify(m.makeOrNull(true)), JSON.stringify(m.makeOrNull(false))].join('|'); })()",
        ),
      )
    }
  }

  @Test
  fun `modules materialize lazily with stable identity`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register("Lazy", sharedMath) {
        function("add", AnyType(TypeDescriptor.Int), AnyType(TypeDescriptor.Int), returns = AnyType(TypeDescriptor.Int))
      }
      runtime.moduleRegistry.register("Other", sharedMath) {
        function("multiply", AnyType(TypeDescriptor.Int), AnyType(TypeDescriptor.Int), returns = AnyType(
          TypeDescriptor.Int,
        ))
      }

      // Repeated reads return the same cached object.
      assertEquals("true", runtime.evaluateAsString("expo.modules.Lazy === expo.modules.Lazy"))
      assertEquals("42", runtime.evaluateAsString("expo.modules.Lazy.add(40, 2)"))
      // Unregistered names read as plain undefined.
      assertEquals("undefined", runtime.evaluateAsString("typeof expo.modules.Nope"))
      // The namespace enumerates every registered module, materialized or not.
      assertEquals(
        "Lazy,Other",
        runtime.evaluateAsString("Object.keys(expo.modules).sort().join()"),
      )
    }
  }

  @Test
  fun `a registry populated before the runtime exists works`() {
    val registry = ModuleRegistry()
    registry.register("Math", sharedMath) {
      function("add", AnyType(TypeDescriptor.Int), AnyType(TypeDescriptor.Int), returns = AnyType(TypeDescriptor.Int))
    }
    HermesRuntime(registry).use { runtime ->
      assertEquals("42", runtime.evaluateAsString("expo.modules.Math.add(40, 2)"))
    }
  }

  @Test
  fun `modules registered after runtime creation are visible to JS`() {
    HermesRuntime().use { runtime ->
      // Nothing crosses JNI at registration, so a name is only fixed once JS materializes it —
      // a module added later is picked up like any other.
      assertEquals("undefined", runtime.evaluateAsString("typeof expo.modules.Late"))
      runtime.moduleRegistry.register("Late", sharedMath) {
        function("add", AnyType(TypeDescriptor.Int), AnyType(TypeDescriptor.Int), returns = AnyType(TypeDescriptor.Int))
      }
      assertEquals("42", runtime.evaluateAsString("expo.modules.Late.add(40, 2)"))
      assertEquals("Late", runtime.evaluateAsString("Object.keys(expo.modules).join()"))
    }
  }

  @Test
  fun `each runtime asks its own registry`() {
    HermesRuntime().use { first ->
      HermesRuntime().use { second ->
        first.moduleRegistry.register("OnlyFirst", sharedMath) {
          function("add", AnyType(TypeDescriptor.Int), AnyType(TypeDescriptor.Int), returns = AnyType(
            TypeDescriptor.Int,
          ))
        }
        assertEquals("42", first.evaluateAsString("expo.modules.OnlyFirst.add(40, 2)"))
        assertEquals("undefined", second.evaluateAsString("typeof expo.modules.OnlyFirst"))
      }
    }
  }

  @Test
  fun `native states chain on a single object`() {
    HermesRuntime().use { runtime ->
      TestSupport.install(runtime)
      // Standalone chain mechanics: coexisting typed states, lookup, same-type shadowing.
      assertEquals("ok", runtime.evaluateAsString("ExpoTestSupport.__nativeStateChainSmoke()"))
      // A materialized module's ModuleNativeState is a chain node and owns the receiver plus all
      // function binders/property metadata, including each component's lazy resolution cache.
      runtime.moduleRegistry.register("Chained", sharedMath) {
        function("add", AnyType(TypeDescriptor.Int), AnyType(TypeDescriptor.Int), returns = AnyType(TypeDescriptor.Int))
        property("answer", AnyType(TypeDescriptor.Int))
      }
      assertEquals(
        "ok",
        runtime.evaluateAsString("ExpoTestSupport.__nativeStateChainSmoke(expo.modules.Chained)"),
      )
      // Callbacks resolve their receiver and definition from the shared state, even when a
      // function is detached from the module object.
      assertEquals(
        "84",
        runtime.evaluateAsString(
          "(() => { const m = expo.modules.Chained; const add = m.add; " +
            "return add(40, 2) + m.answer; })()",
        ),
      )
    }
  }

  @Test
  fun `the materialized module object pins its Kotlin instance`() {
    HermesRuntime().use { runtime ->
      // A fresh (non-singleton) receiver: the JS module object's native state holds the global
      // reference to exactly this instance, so its mutable state persists across JS calls.
      runtime.moduleRegistry.register(CounterFixture())
      assertEquals("1", runtime.evaluateAsString("expo.modules.Counter.increment()"))
      assertEquals("2", runtime.evaluateAsString("expo.modules.Counter.increment()"))
      assertEquals("3", runtime.evaluateAsString("expo.modules.Counter.increment()"))
    }
  }

  @Test
  fun `a module maps to its JavaScript object and back once materialized`() {
    HermesRuntime().use { runtime ->
      val module = CounterFixture()
      runtime.moduleRegistry.register(module)

      // Nothing exists in this runtime until JavaScript reads the module.
      assertNull(runtime.jsObjectOf(module))

      runtime.evaluate("globalThis.m = expo.modules.Counter;")
      val moduleObject = assertNotNull(runtime.jsObjectOf(module))
      runtime.global()["fromKotlin"] = moduleObject
      assertEquals("true", runtime.evaluateAsString("fromKotlin === m"))

      // And back: the module object stands for exactly the registered instance.
      assertSame(module, moduleObject.nativeInstance())
      assertNull(runtime.createObject().nativeInstance())
    }
  }

  @Test
  fun `one module instance has one JavaScript object per runtime`() {
    // The runtimes share instances, and so must share the context those instances belong to.
    val context = ExpoContext()
    val module = CounterFixture()
    HermesRuntime(context = context).use { first ->
      HermesRuntime(context = context).use { second ->
        first.moduleRegistry.register(module)
        second.moduleRegistry.register(module)

        first.evaluate("expo.modules.Counter.increment();")
        assertNotNull(first.jsObjectOf(module))
        // The second runtime has not materialized it yet, even though the instance already has an id.
        assertNull(second.jsObjectOf(module))

        second.evaluate("expo.modules.Counter.increment();")
        val inSecond = assertNotNull(second.jsObjectOf(module))
        assertSame(module, inSecond.nativeInstance())
        assertSame(module, assertNotNull(first.jsObjectOf(module)).nativeInstance())
      }
    }
  }

  @Test
  fun `functions and property accessors resolve independently on first invocation`() {
    HermesRuntime().use { runtime ->
      // Registration and module materialization only install metadata and JS callbacks. Unused
      // invalid exports must not prevent valid functions and accessors from running.
      runtime.moduleRegistry.register("Bad", sharedMath) {
        function("add", AnyType(TypeDescriptor.Int), AnyType(TypeDescriptor.Int), returns = AnyType(TypeDescriptor.Int))
        function("nope", returns = AnyType(TypeDescriptor.Int))
        // MathUtils.answer has a getter but no setter. Reading it must not resolve setAnswer.
        property("answer", AnyType(TypeDescriptor.Int), mutable = true)
        // Neither accessor exists, but this completely unused property must remain unresolved.
        property("unused", AnyType(TypeDescriptor.Int))
      }

      assertEquals("function", runtime.evaluateAsString("typeof expo.modules.Bad.nope"))
      assertEquals("42", runtime.evaluateAsString("expo.modules.Bad.add(40, 2)"))
      assertEquals("42", runtime.evaluateAsString("expo.modules.Bad.answer"))

      val error = assertFailsWith<RuntimeException> {
        runtime.evaluateAsString("expo.modules.Bad.nope()")
      }
      assertTrue("nope" in (error.message ?: ""), "expected the error to name the method, got: $error")

      // Setter resolution is independent from the getter and fails only on the first write.
      val setterError = assertFailsWith<RuntimeException> {
        runtime.evaluateAsString("'use strict'; expo.modules.Bad.answer = 7")
      }
      assertTrue(
        "setAnswer" in (setterError.message ?: ""),
        "expected the error to name the setter, got: $setterError",
      )

      // Failed lookups do not poison the module or its successfully cached components.
      assertEquals("42", runtime.evaluateAsString("expo.modules.Bad.add(20, 22)"))
      assertEquals("42", runtime.evaluateAsString("expo.modules.Bad.answer"))
      assertFailsWith<RuntimeException> {
        runtime.evaluateAsString("expo.modules.Bad.nope()")
      }
    }
  }

  @Test
  fun `registering the same module name twice throws`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register("Dup", sharedMath) {
        function("add", AnyType(TypeDescriptor.Int), AnyType(TypeDescriptor.Int), returns = AnyType(TypeDescriptor.Int))
      }
      // The duplicate check is pure Kotlin — it fails before anything crosses JNI.
      assertFailsWith<IllegalArgumentException> {
        runtime.moduleRegistry.register("Dup", sharedMath) {
          function("multiply", AnyType(TypeDescriptor.Int), AnyType(TypeDescriptor.Int), returns = AnyType(
            TypeDescriptor.Int,
          ))
        }
      }
      // The original registration is untouched.
      assertEquals("42", runtime.evaluateAsString("expo.modules.Dup.add(40, 2)"))
    }
  }

  @Test
  fun `module metadata over the fixed buffer capacity fails the first access`() {
    HermesRuntime().use { runtime ->
      // A single ~300 KiB function name exceeds the 256 KiB bridge buffer; the buffer never
      // grows, and the descriptor only crosses JNI lazily, so the failure surfaces on the first
      // JS access rather than at registration.
      runtime.moduleRegistry.register("Huge", sharedMath) {
        function(
          "f".repeat(300 * 1024),
          AnyType(TypeDescriptor.Int),
          AnyType(TypeDescriptor.Int),
          returns = AnyType(TypeDescriptor.Int),
          methodName = "add",
        )
      }
      val error = assertFailsWith<RuntimeException> {
        runtime.evaluateAsString("expo.modules.Huge")
      }
      assertTrue(
        "does not fit" in (error.message ?: ""),
        "expected the error to blame the fixed buffer, got: $error",
      )
    }
  }

  // --- async exports ---------------------------------------------------------------------------

  @Test
  fun `a suspend export returns a promise and settles only when the JS thread runs`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(sharedAsync)

      assertEquals(
        "[object Promise]",
        runtime.evaluateAsString(
          "globalThis.p = expo.modules.Async.immediate(1);" +
            "Object.prototype.toString.call(globalThis.p)",
        ),
        "an async export must hand JavaScript a promise straight away",
      )

      runtime.evaluate("globalThis.out = null; globalThis.p.then((v) => { globalThis.out = v; });")
      assertEquals(
        "null",
        runtime.evaluateAsString("globalThis.out"),
        "nothing may settle until something drains the JS thread's jobs",
      )

      assertEquals("immediate:1", runtime.awaitSettled("globalThis.out"))
    }
  }

  @Test
  fun `a suspend export that really suspends still settles on the JS thread`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(sharedAsync)

      runtime.evaluate(
        "globalThis.out = null;" +
          "expo.modules.Async.delayed(2).then((v) => { globalThis.out = v; });",
      )
      assertEquals("delayed:2", runtime.awaitSettled("globalThis.out"))
    }
  }

  @Test
  fun `an exception from a suspend export rejects the promise`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(sharedAsync)

      runtime.evaluate(
        "globalThis.out = null;" +
          "expo.modules.Async.boom().catch((e) => {" +
          "  globalThis.out = e.code + '/' + e.message + '/' + (typeof e.nativeStack);" +
          "});",
      )
      assertEquals(
        "java.lang.IllegalStateException/kaboom/string",
        runtime.awaitSettled("globalThis.out"),
        "a rejection carries the exception's class, message and Kotlin stack",
      )
    }
  }

  @Test
  fun `async results cross on every transport`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(sharedAsync)

      // A buffered argument, decoded before the coroutine starts, and a buffered Int result.
      runtime.evaluate(
        "globalThis.a = null; expo.modules.Async.sum([1,2,3,4]).then((v) => { globalThis.a = v; });",
      )
      assertEquals("10", runtime.awaitSettled("globalThis.a"))

      // A scalar pinned off the buffer: it crosses boxed, in a JNI slot.
      runtime.evaluate(
        "globalThis.b = null; expo.modules.Async.twice(21).then((v) => { globalThis.b = v; });",
      )
      assertEquals("42", runtime.awaitSettled("globalThis.b"))

      // Unit resolves with undefined rather than leaving the promise pending.
      runtime.evaluate(
        "globalThis.c = null;" +
          "expo.modules.Async.nothing().then((v) => { globalThis.c = (v === undefined); });",
      )
      assertEquals("true", runtime.awaitSettled("globalThis.c"))

      // Far past the 256 KB buffer, so the result takes the overflow slot.
      runtime.evaluate(
        "globalThis.d = null;" +
          "expo.modules.Async.huge(300000).then((v) => { globalThis.d = v.length; });",
      )
      assertEquals("300000", runtime.awaitSettled("globalThis.d"))
    }
  }

  @Test
  fun `closing a runtime with a pending promise does not crash`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(AsyncModule())

      runtime.evaluate("globalThis.stuck = expo.modules.Async.forever();")
      // The coroutine is parked forever, so its resolve/reject pair is still in the promise table
      // when `use` closes the runtime. Tearing that down must destroy them cleanly.
      runtime.drainJobs()
    }
  }

  @Test
  fun `an async export whose body never suspends settles inside the call`() {
    HermesRuntime().use { runtime ->
      runtime.moduleRegistry.register(sharedAsync)

      // Dispatchers.Unconfined starts the body on the calling thread, so a body that never really
      // suspends has already produced its value by the time the trampoline returns — and the invoker
      // settles it right there, before the promise reaches JavaScript. Nothing goes to the job queue,
      // so `drainJobs` finds none and only runs the `.then` the settle queued.
      runtime.evaluate("globalThis.out = null; expo.modules.Async.immediate(7).then((v) => { globalThis.out = v; });")
      assertFalse(runtime.drainJobs(), "the settle went through the job queue instead of the call")
      assertEquals("immediate:7", runtime.evaluateAsString("globalThis.out"))
    }
  }

  @Test
  fun `the expo modules namespace is read-only`() {
    HermesRuntime().use { runtime ->
      assertEquals(
        "threw",
        runtime.evaluateAsString(
          "try { expo.modules.Anything = 1; 'no error'; } catch (e) { 'threw'; }",
        ),
      )
    }
  }
}
