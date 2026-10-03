package io.github.expo.modules.v2.modules

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.binary.ModuleDescriptorEncoder
import io.github.expo.modules.v2.binary.newSharedView
import io.github.expo.modules.v2.core.ExpoModulesV2
import io.github.expo.modules.v2.ExpoContext
import java.nio.BufferOverflowException

class ModuleRegistry internal constructor(private val context: ExpoContext) {
  private class Entry(
    val module: Module,
    val functions: List<ModuleFunctionDefinition>,
    val properties: List<ModulePropertyDefinition>,
    val sharedClasses: List<ModuleSharedClassDefinition>,
    val events: List<ModuleEventDefinition>,
  )

  /** Guarded by itself: the context reads it from any thread, and JavaScript from the JS thread. */
  private val modules = LinkedHashMap<String, Entry>()

  fun register(name: String, module: Module, build: ModuleBuilder.() -> Unit) {
    add(name, module, ModuleBuilder().apply(build))
  }

  /**
   * Registers a module that declares its exports with [io.github.expo.modules.v2.JS]. The name and
   * every export come from the definition the compiler plugin generated on [module].
   */
  fun register(module: Module) {
    val builder = ModuleBuilder()
    val name = requireNotNull(module.`define$ExpoModulesV2`(builder)) {
      "${module.javaClass.name} is not annotated with @JS, so it declares no exports — annotate it, " +
        "or register it with register(name, module) { ... }"
    }
    add(name, module, builder)
  }

  /** Every registered module, in the order they were registered. */
  internal fun modules(): List<Module> = synchronized(modules) { modules.values.map { it.module } }

  /** The registered module of [type], or null when there is none. */
  internal fun <T : Module> module(type: Class<T>): T? {
    val module = synchronized(modules) { modules.values.firstOrNull { type.isInstance(it.module) }?.module }
    return module?.let(type::cast)
  }

  private fun add(name: String, module: Module, definition: ModuleBuilder) {
    synchronized(modules) {
      require(name !in modules) { "Module '$name' is already registered" }

      module.bindContext(context)
      modules[name] = Entry(
        module,
        definition.functions,
        definition.properties,
        definition.sharedClasses,
        definition.events,
      )
    }
  }

  @Suppress("unused")
  @CalledFromNative(by = "expo-modules-v2/jni/JModuleRegistry.h")
  private fun encodeModule(name: String): Any? {
    val entry = synchronized(modules) { modules[name] } ?: return null
    try {
      ModuleDescriptorEncoder.encode(
        BinaryBuffer.newSharedView(),
        entry.functions,
        entry.properties,
        entry.sharedClasses,
        entry.events,
      )
    } catch (overflow: BufferOverflowException) {
      throw IllegalArgumentException(
        "Module '$name' registration metadata does not fit in the fixed bridge buffer",
        overflow,
      )
    }
    return entry.module
  }

  @Suppress("unused")
  @CalledFromNative(by = "expo-modules-v2/jni/JModuleRegistry.h")
  private fun encodeModuleNames(): Int {
    val buf = BinaryBuffer.newSharedView()

    try {
      buf.putStringCollection(synchronized(modules) { modules.keys.toList() })
    } catch (overflow: BufferOverflowException) {
      throw IllegalArgumentException(
        "The registered module names do not fit in the fixed bridge buffer",
        overflow,
      )
    }

    return buf.position
  }

  companion object {
    init {
      ExpoModulesV2.load()
    }
  }
}
