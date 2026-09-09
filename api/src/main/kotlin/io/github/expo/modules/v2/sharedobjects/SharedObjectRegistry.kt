package io.github.expo.modules.v2.sharedobjects

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.SharedRef
import io.github.expo.modules.v2.binary.ModuleDescriptorEncoder
import io.github.expo.modules.v2.binary.newSharedView
import io.github.expo.modules.v2.cache.MultiKeyCache
import io.github.expo.modules.v2.jni.jniDescriptor
import io.github.expo.modules.v2.loader.ExpoClassLoader
import io.github.expo.modules.v2.modules.ModuleBuilder
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.TypeDescriptor
import java.nio.BufferOverflowException

object SharedObjectRegistry {
  internal class ClassEntry(
    val id: SharedClassId,
    val sharedClass: Class<out SharedObject>,
    val jsName: String,
    // TODO(@lukmccall): use immutable structure instead of ModuleBuilder
    val definition: ModuleBuilder,
  )

  private val classes = MultiKeyCache<Class<*>, SharedClassId, ClassEntry>()

  init {
    register("SharedRef", SharedRef::class.java)
  }

  @JvmStatic
  fun register(
    jsName: String,
    sharedClass: Class<out SharedObject>,
    definition: ModuleBuilder,
  ): Int {
    classes.get(sharedClass)?.let { return it.id.value }

    definition.alsoDeclaringRefType(sharedClass)

    val entry = ClassEntry(
      id = SharedClassId.next(),
      sharedClass = sharedClass,
      jsName = jsName,
      definition = definition,
    )
    classes.put(sharedClass, entry.id, entry)

    return entry.id.value
  }

  fun register(
    jsName: String,
    sharedClass: Class<out SharedObject>,
    build: ModuleBuilder.() -> Unit = {},
  ): Int = register(jsName, sharedClass, ModuleBuilder().apply(build))

  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JSharedObjectRegistry.h")
  fun sharedClassOf(classId: Int): Class<*>? = classes.get(SharedClassId(classId))?.sharedClass

  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JSharedObjectRegistry.h")
  fun classDescriptorOf(classId: Int): String? = sharedClassOf(classId)?.jniDescriptor

  internal fun classIdFor(sharedClass: Class<out SharedObject>): SharedClassId = entryFor(sharedClass).id

  internal fun entryOrNull(sharedClass: Class<out SharedObject>): ClassEntry? {
    classes.get(sharedClass)?.let { return it }
    ExpoClassLoader.loadAndInitializeClass(sharedClass)
    return classes.get(sharedClass)
  }

  private fun entryFor(sharedClass: Class<out SharedObject>): ClassEntry {
    entryOrNull(sharedClass)?.let { return it }

    throw IllegalArgumentException(
      "${sharedClass.name} is not annotated with @JS, so it declares no exports - annotate it, or " +
        "describe it with SharedObjectRegistry.register(name, ${sharedClass.simpleName}::class.java) " +
        "{ ... } before anything names it",
    )
  }

  private fun ModuleBuilder.alsoDeclaringRefType(
    sharedClass: Class<out SharedObject>,
  ): ModuleBuilder = apply {
    if (SharedRef::class.java.isAssignableFrom(sharedClass)) {
      property(
        "nativeRefType",
        AnyType(TypeDescriptor.Simple(String::class.java, false))
      )
    }
  }

  /**
   * The class id of [instance]'s shared class. The native side calls this once per instance, when
   * it builds the instance's native state; the per-instance id itself lives on
   * [io.github.expo.modules.v2.ExpoObject.objectId] and is managed natively.
   */
  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JSharedObjectRegistry.h")
  fun classIdOf(instance: SharedObject): Int = entryFor(instance.javaClass).id.value

  /**
   * Runs the release hook. The native side calls this exactly once per native state, guarded by
   * that state's released flag, so no idempotency guard is needed here.
   */
  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JSharedObjectRegistry.h")
  fun release(instance: SharedObject) {
    // A released object has no JavaScript side left to emit to, so nothing observes its events.
    instance.events?.values?.forEach { it.detachAll() }
    instance.sharedObjectDidRelease()
  }

  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JSharedObjectRegistry.h")
  fun encodeClass(classId: Int): String? {
    val entry = classes.get(SharedClassId(classId)) ?: return null

    try {
      ModuleDescriptorEncoder.encode(
        BinaryBuffer.newSharedView(),
        entry.definition.functions,
        entry.definition.properties,
        events = entry.definition.events,
      )
    } catch (overflow: BufferOverflowException) {
      throw IllegalArgumentException(
        "Shared object class '${entry.jsName}' metadata does not fit in the fixed bridge buffer",
        overflow,
      )
    }

    return entry.jsName
  }
}
