package io.github.expo.modules.v2.modules

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.binary.ModuleDescriptorEncoder
import io.github.expo.modules.v2.binary.newSharedView
import io.github.expo.modules.v2.core.ExpoModulesV2
import io.github.expo.modules.v2.ExpoContext
import java.nio.BufferOverflowException

@CalledFromNative(by = "expo-modules-v2/jni/JModuleRegistry.h")
class ModuleRegistry internal constructor(private val context: ExpoContext) {
  /**
   * A registered module and its exports. A module the compiler plugin generated is registered by
   * the name it reports, and [definition] runs its `define$ExpoModulesV2` the first time JavaScript
   * reads it: an app registers every module it ships at startup, but touches only a few of them
   * while it launches, and defining one loads its record codecs and builds its converters.
   */
  private class Entry(
    val name: String,
    val module: Module,
    /** Guarded by this entry. Null until the module is defined. */
    private var definition: ModuleBuilder?,
  ) {
    fun definition(): ModuleBuilder = synchronized(this) {
      definition?.let { return it }

      val builder = ModuleBuilder()
      val defined = module.`define$ExpoModulesV2`(builder)
      check(defined == name) {
        "${module.javaClass.name} reported the name '$name' but defined itself as '$defined'"
      }
      definition = builder
      builder
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
   * The exports are defined when JavaScript first reads the module, so a module the app ships but
   * does not use at launch costs next to nothing here. A module compiled by an older plugin, which
   * cannot report its name on its own, is defined at once.
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

  /** [definition] is null for a module defined when JavaScript first reads it. */
  private fun add(name: String, module: Module, definition: ModuleBuilder?) {
    synchronized(modules) {
      require(name !in modules) { "Module '$name' is already registered" }

      module.bindContext(context)
      modules[name] = Entry(name, module, definition)
    }
  }

  @Suppress("unused")
  @CalledFromNative(by = "expo-modules-v2/jni/JModuleRegistry.h")
  private fun encodeModule(name: String): Any? {
    val entry = synchronized(modules) { modules[name] } ?: return null
    // Outside the registry's lock: defining a module can take a while, and blocks only this entry.
    val definition = entry.definition()
    try {
      ModuleDescriptorEncoder.encode(
        BinaryBuffer.newSharedView(),
        definition.functions,
        definition.properties,
        definition.sharedClasses,
        definition.events,
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
