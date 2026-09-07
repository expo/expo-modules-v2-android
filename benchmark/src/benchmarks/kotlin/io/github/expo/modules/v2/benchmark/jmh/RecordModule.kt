package io.github.expo.modules.v2.benchmark.jmh

import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.testsupport.HermesRuntime

/**
 * The module under test for [RecordPairFastBenchmark] and friends: the record path (data class
 * <-> JS object, positional EXTERNAL_SCHEMA buffer format, trampoline payload) next to the same
 * payload crossing as untyped maps — the representation module authors would otherwise use.
 *
 * Dynamic maps are not buffer-safe, so the maps side crosses element-wise as JNI object slots with
 * direct invocation; the records side rides the binary buffer through trampolines. [FlaggedItem]
 * sizes the third path: a record whose schema contains a dynamic field crosses decomposed as a Map
 * object slot both ways.
 *
 * Every one of those transports is what the plugin picks by default, so no declaration below
 * carries a `@BufferMode` and the measurement is of the generated trampolines themselves.
 */
@Record
data class Item(val id: Int, val name: String, val x: Double, val flag: Boolean) : io.github.expo.modules.v2.records.Record

@Record
data class Order(val item: Item, val tags: List<String>) : io.github.expo.modules.v2.records.Record

/** A record whose schema contains a dynamic field: not buffer-safe, crosses as a Map slot. */
@Record(bufferSafe = false)
data class FlaggedItem(val id: Int, val name: String, val meta: Map<String, Any>) : io.github.expo.modules.v2.records.Record

@ExpoModule(name = "Rec")
class RecordOps : Module() {
  @JS
  fun echoOne(value: Item): Item = value

  @JS
  fun echoOneMap(value: Map<String, Any?>): Map<String, Any?> = value

  @JS
  fun echoOneFlagged(value: FlaggedItem): FlaggedItem = value

  @JS
  fun echo(values: List<Item>): List<Item> = values

  @JS
  fun echoMaps(values: List<Map<String, Any?>>): List<Map<String, Any?>> = values

  @JS
  fun sumX(values: List<Item>): Double = values.sumOf { it.x }

  @JS
  fun sumXMaps(values: List<Map<String, Any?>>): Double = values.sumOf { it["x"] as Double }

  @JS
  fun make(count: Int): List<Item> =
    List(count) { Item(it, "item$it", it * 0.5, it % 2 == 0) }

  @JS
  fun makeMaps(count: Int): List<Map<String, Any?>> =
    List(count) { mapOf("id" to it, "name" to "item$it", "x" to it * 0.5, "flag" to (it % 2 == 0)) }

  @JS
  fun echoOrders(values: List<Order>): List<Order> = values
}

/**
 * Registers the module as `globalThis.Rec` and pre-materializes it, so descriptor exchange does
 * not land inside a measured loop.
 */
internal fun registerRecordModule(runtime: HermesRuntime) {
  runtime.moduleRegistry.register(RecordOps())
  runtime.evaluate("globalThis.Rec = expo.modules.Rec")
}
