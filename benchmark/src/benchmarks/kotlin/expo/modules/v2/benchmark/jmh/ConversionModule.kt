package expo.modules.v2.benchmark.jmh

import expo.modules.v2.annotations.Buffer
import expo.modules.v2.annotations.JS
import expo.modules.v2.annotations.Record
import expo.modules.v2.testsupport.HermesRuntime
import expo.modules.v2.modules.Module

/**
 * The module under test for [ConversionTransportFastBenchmark] and friends: one registered method
 * per transport, so a payload can be measured through the buffered path and the element-wise /
 * direct-JNI path without changing anything else.
 *
 * - Buffer-safe types (`echoTyped`, `sumTyped`, `echoRecords`): trampoline payload — the whole
 *   structure serialized into the shared direct ByteBuffer, one JNI crossing.
 * - Dynamic types (`echo`, `echoMap`, `echoMaps` — `List<Any?>`/`Map<String, Any?>`): JNI object
 *   slots converted element-wise; these can carry JSI handles, so they never ride the buffer.
 * - `echoArray`: the DoubleArray baseline — bulk JNI copies both ways, no buffer, no payload limit.
 *
 * Each `*Direct`/`*Buffered` pair is two Kotlin functions exported under the names the cases expect,
 * because one function carries exactly one transport. `Buffer.NO` and `Buffer.YES` name the side that
 * differs from the default, so the declarations read as the comparison being measured.
 */
@JS(name = "Bench")
class ConversionOps : Module() {
  // Dynamic containers can carry JSI handles, so they are never buffer-safe: JNI object slots.
  @JS
  fun echo(values: List<Any?>): List<Any?> = values

  /** String payloads through the buffer: exercises the adaptive ASCII/UTF-16 string codec. */
  @JS
  fun echoStrings(values: List<String>): List<String> = values

  @JS
  fun echoTyped(values: List<Double>): List<Double> = values

  /** The same typed list as a JList slot, which boxes every element. */
  @JS(name = "echoTypedDirect", buffer = Buffer.NO)
  fun echoTypedDirect(values: List<Double>): List<Double> = values

  @JS
  fun echoMap(map: Map<String, Any?>): Map<String, Any?> = map

  @JS
  fun sumTyped(values: List<Double>): Double = values.sum()

  /** The DoubleArray baseline: bulk JNI region copies both ways, and no payload limit. */
  @JS(name = "echoArray", buffer = Buffer.NO)
  fun echoArrayDirect(values: DoubleArray): DoubleArray = values

  @JS(name = "echoArrayBuffered", buffer = Buffer.YES)
  fun echoArrayBuffered(values: DoubleArray): DoubleArray = values

  @JS(name = "echoString", buffer = Buffer.NO)
  fun echoStringDirect(value: String): String = value

  @JS(name = "echoStringBuffered")
  fun echoStringBuffered(value: String): String = value

  @JS(name = "echoBoxed", buffer = Buffer.NO)
  fun echoBoxedDirect(value: Int?): Int? = value

  @JS(name = "echoBoxedBuffered")
  fun echoBoxedBuffered(value: Int?): Int? = value

  @JS
  fun echoSmall(value: SmallRecord): SmallRecord = value

  @JS
  fun echoOne(value: BenchRecord): BenchRecord = value

  @JS
  fun echoWide(value: WideRecord): WideRecord = value

  @JS
  fun echoRecordsWide(values: List<WideRecord>): List<WideRecord> = values

  @JS
  fun echoRecords(values: List<BenchRecord>): List<BenchRecord> = values

  @JS
  fun echoMaps(values: List<Map<String, Any?>>): List<Map<String, Any?>> = values
}

/** The smallest useful record: two scalar fields. */
@Record
data class SmallRecord(val id: Int, val x: Double) : expo.modules.v2.records.Record

/** A wide record: twelve mixed fields, the shape of a chunky options/response object. */
@Record
data class WideRecord(
  val id: Int,
  val name: String,
  val desc: String,
  val x: Double,
  val y: Double,
  val z: Double,
  val count: Int,
  val ratio: Double,
  val active: Boolean,
  val tag: String,
  val weight: Double,
  val rank: Int,
) : expo.modules.v2.records.Record

@Record
data class BenchRecord(val id: Int, val name: String, val x: Double, val flag: Boolean) : expo.modules.v2.records.Record

/**
 * Registers the module as `globalThis.Bench` and pre-materializes it, so descriptor exchange does
 * not land inside a measured loop.
 */
internal fun registerConversionModule(runtime: HermesRuntime) {
  runtime.moduleRegistry.register(ConversionOps())
  runtime.evaluate("globalThis.Bench = expo.modules.Bench")
}
