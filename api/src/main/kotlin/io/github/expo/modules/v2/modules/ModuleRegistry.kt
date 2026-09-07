package io.github.expo.modules.v2.modules

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.binary.ModuleDescriptorEncoder
import io.github.expo.modules.v2.binary.newSharedView
import io.github.expo.modules.v2.core.ExpoModulesV2
import java.nio.BufferOverflowException

class ModuleRegistry {
  private class Entry(
    val module: Module,
    val functions: List<ModuleFunctionDefinition>,
    val properties: List<ModulePropertyDefinition>,
    val sharedClasses: List<ModuleSharedClassDefinition>,
  )

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

  private fun add(name: String, module: Module, definition: ModuleBuilder) {
    require(name !in modules) { "Module '$name' is already registered" }

    modules[name] = Entry(module, definition.functions, definition.properties, definition.sharedClasses)
  }

  @Suppress("unused")
  @CalledFromNative(by = "expo-modules-v2/jni/JModuleRegistry.h")
  private fun encodeModule(name: String): Any? {
    val entry = modules[name] ?: return null
    try {
      ModuleDescriptorEncoder.encode(
        entry.functions,
        entry.properties,
        BinaryBuffer.newSharedView(),
        entry.sharedClasses,
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
      buf.putStringCollection(modules.keys)
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
