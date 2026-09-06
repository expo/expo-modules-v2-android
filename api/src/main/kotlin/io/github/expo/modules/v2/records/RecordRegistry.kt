package io.github.expo.modules.v2.records

import io.github.expo.modules.v2.cache.Cache
import io.github.expo.kolibri.CalledFromNative
import io.github.expo.modules.v2.loader.ExpoClassLoader

object RecordRegistry {
  private val byClass = Cache<Class<*>, RecordType<*>>()
  private val byId = Cache<SchemaId, RecordType<*>>()

  @Volatile
  private var _nextRecordId = SchemaId(1)

  private val nextRecordId: SchemaId
    get() {
      return _nextRecordId++
    }

  fun register(codec: RecordCodec<*>) {
    requireNotNull(codec.recordClass) {
      "${codec.javaClass.name} ran `RecordRegistry.register(this)` before `recordClass` was " +
        "initialized — declare `override val recordClass` above the `init` block"
    }
    registerCodec(codec)
  }

  internal fun <T : Record> typeFor(codec: RecordCodec<T>): RecordType<T> =
    existingTypeFor(codec) ?: throw IllegalStateException(
      "RecordCodec ${codec.javaClass.name} is not registered - add " +
        "`init { RecordRegistry.register(this) }` to the codec, declared after `recordClass`",
    )

  internal fun typeFor(schemaId: SchemaId): RecordType<*> =
    byId.get(schemaId) ?: throw IllegalStateException(
      "Unknown external schema id $schemaId - register it via RecordRegistry.register first",
    )

  /**
   * The registered [RecordType] for [clazz], or `null` when the class is not a record.
   *
   * A codec registers itself from its owner's `<clinit>`, so a miss can simply mean the class has
   * not been initialized yet. Generated code refers to another record with a bare `Other::class.java`,
   * which resolves the class but — per JVMS 5.5 — does not initialize it. So on a miss we force
   * initialization once and look again.
   *
   * The [Record] guard is what keeps the name lookup off the hot path.
   * [io.github.expo.modules.v2.types.DynamicTypes.kindOf] classifies every object crossing an `ANY` slot and
   * falls through to here for anything it does not recognise — a lambda, a `Set`, any user class —
   * and none of those can be a record. ([dynamicRecordToMap] never reaches this at all: it runs
   * only after `kindOf` already answered `RECORD`, so `byClass` has the class by then.)
   */
  internal fun typeFor(clazz: Class<*>): RecordType<*>? =
    byClass.get(clazz) ?: forceInitAndRetry(clazz)

  private fun forceInitAndRetry(clazz: Class<*>): RecordType<*>? {
    if (!Record::class.java.isAssignableFrom(clazz)) {
      return null
    }

    ExpoClassLoader.loadAndInitializeClass(clazz)

    return byClass.get(clazz)
  }

  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JRecordRegistry.h")
  fun dynamicRecordToMap(value: Any): Map<String, Any?>? {
    val type = typeFor(value.javaClass)
      ?: return null

    return type.anyCodec.toMap(value as Record)
  }

  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JRecordRegistry.h")
  fun fetchSchema(schemaId: Int): RecordSchemaData {
    val type = typeFor(SchemaId(schemaId))
    return type.toData()
  }

  @Synchronized
  private fun <T : Record> registerCodec(codec: RecordCodec<T>): RecordType<T> {
    existingTypeFor(codec)?.let {
      error("Codec has been registered before")
    }

    val schemaId = nextRecordId
    return RecordType(schemaId, codec).also { type ->
      byClass.put(codec.recordClass, type)
      byId.put(schemaId, type)
    }
  }

  private fun <T : Record> existingTypeFor(codec: RecordCodec<T>): RecordType<T>? {
    val existing = byClass.get(codec.recordClass) ?: return null
    require(existing.codec === codec) {
      "Class ${codec.recordClass.name} is already registered with a different RecordCodec"
    }
    @Suppress("UNCHECKED_CAST")
    return existing as RecordType<T>
  }
}
