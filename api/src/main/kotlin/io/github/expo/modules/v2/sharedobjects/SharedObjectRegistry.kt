package io.github.expo.modules.v2.sharedobjects

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.binary.ModuleDescriptorEncoder
import io.github.expo.modules.v2.binary.newSharedView
import io.github.expo.modules.v2.cache.MultiKeyCache
import io.github.expo.modules.v2.jni.jniDescriptor
import io.github.expo.modules.v2.loader.ExpoClassLoader
import io.github.expo.modules.v2.modules.ModuleBuilder
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.TypeDescriptor
import java.nio.BufferOverflowException
import java.util.concurrent.atomic.AtomicInteger

object SharedObjectRegistry {
  internal class ClassEntry(
    val id: SharedClassId,
    val sharedClass: Class<out SharedObject>,
    val jsName: String,
    // TODO(@lukmccall): use immutable structure instead of ModuleBuilder
    val definition: ModuleBuilder,
  ) {
    private val _nextObjectId = AtomicInteger(1)
    fun nextObjectId() = _nextObjectId.getAndIncrement()
  }

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

  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JSharedObjectRegistry.h")
  fun attach(instance: SharedObject): Long {
    val entry = entryFor(instance.javaClass)

    val objectId = when (val current = instance.sharedObjectId) {
      SharedObject.RELEASED -> throw IllegalStateException(
        "${instance.javaClass.name} was released and cannot be passed to JavaScript again",
      )

      SharedObject.UNASSIGNED -> entry.nextObjectId().also {
        instance.sharedObjectId = it
      }

      else -> current
    }

    return (entry.id.value.toLong() shl 32) or (objectId.toLong() and 0xFFFFFFFFL)
  }

  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JSharedObjectRegistry.h")
  fun release(instance: SharedObject) {
    val isAlive = instance.sharedObjectId != SharedObject.RELEASED

    if (isAlive) {
      instance.sharedObjectDidRelease()
      instance.sharedObjectId = SharedObject.RELEASED
    }
  }

  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JSharedObjectRegistry.h")
  fun encodeClass(classId: Int): String? {
    val entry = classes.get(SharedClassId(classId)) ?: return null

    try {
      ModuleDescriptorEncoder.encode(
        entry.definition.functions,
        entry.definition.properties,
        BinaryBuffer.newSharedView(),
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
