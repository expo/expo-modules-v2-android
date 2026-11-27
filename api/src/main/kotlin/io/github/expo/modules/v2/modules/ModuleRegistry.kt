package io.github.expo.modules.v2.modules

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.ExpoContext
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.binary.ModuleDescriptorEncoder
import io.github.expo.modules.v2.binary.newSharedView
import io.github.expo.modules.v2.core.ExpoModulesV2
import java.nio.BufferOverflowException

@CalledFromNative(by = "expo-modules-v2/jni/JModuleRegistry.h")
class ModuleRegistry internal constructor(private val context: ExpoContext) {
  private class Entry(
    val module: Module,
    definition: ModuleBuilder?
  ) {
    lateinit var functions: List<ModuleFunctionDefinition>
      private set
    lateinit var properties: List<ModulePropertyDefinition>
      private set
    lateinit var sharedClasses: List<ModuleSharedClassDefinition>
      private set
    lateinit var events: List<ModuleEventDefinition>
      private set

    init {
      definition?.let(::adopt)
    }

    /** Defines the module, registered as [name], unless it already is. */
    fun define(name: String) {
      synchronized(this) {
        if (::functions.isInitialized) {
          return
        }

        val builder = ModuleBuilder()
        val defined = module.`define$ExpoModulesV2`(builder)
        check(defined == name) {
          "${module.javaClass.name} reported the name '$name' but defined itself as '$defined'"
        }
        adopt(builder)
      }
    }

    private fun adopt(definition: ModuleBuilder) {
      properties = definition.properties
      sharedClasses = definition.sharedClasses
      events = definition.events
      functions = definition.functions
    }
  }

  /** Guarded by itself: the context reads it from any thread, and JavaScript from the JS thread. */
  private val modules = LinkedHashMap<String, Entry>()

  fun register(name: String, module: Module, build: ModuleBuilder.() -> Unit) {
    add(name, module, ModuleBuilder().apply(build))
  }

  /**
   * Registers a module that declares its exports with [io.github.expo.modules.v2.JS]. The name and
   * every export come from the definition the compiler plugin generated on [module].
   *
   * @return the name of the module, under which JavaScript reaches it in `expo.modules`.
   */
  fun register(module: Module): String {
    module.`name$ExpoModulesV2`()?.let { name ->
      add(name, module, definition = null)
      return name
    }

    val builder = ModuleBuilder()
    val name = requireNotNull(module.`define$ExpoModulesV2`(builder)) {
      "${module.javaClass.name} is not annotated with @JS, so it declares no exports - annotate it, " +
        "or register it with register(name, module) { ... }"
    }
    add(name, module, builder)
    return name
  }

  /** Every registered module, in the order they were registered. */
  internal fun modules(): List<Module> = synchronized(modules) { modules.values.map { it.module } }

  /** The registered module of [type], or null when there is none. */
  internal fun <T : Module> module(type: Class<T>): T? {
    val module = synchronized(modules) { modules.values.firstOrNull { type.isInstance(it.module) }?.module }
    return module?.let(type::cast)
  }

  private fun add(name: String, module: Module, definition: ModuleBuilder?) {
    synchronized(modules) {
      require(name !in modules) { "Module '$name' is already registered" }

      module.bindContext(context)
      modules[name] = Entry(module, definition)
    }
  }

  @Suppress("unused")
  @CalledFromNative(by = "expo-modules-v2/jni/JModuleRegistry.h")
  private fun encodeModule(name: String): Any? {
    val entry = synchronized(modules) { modules[name] } ?: return null
    entry.define(name)
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
