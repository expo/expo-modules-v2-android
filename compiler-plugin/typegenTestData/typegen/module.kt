package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Constant
import io.github.expo.modules.v2.Event
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.TypedArray
import io.github.expo.modules.v2.async.Promise
import io.github.expo.modules.v2.async.resolve
import io.github.expo.modules.v2.jsi.JavaScriptObject
import io.github.expo.modules.v2.jsi.JavaScriptValue
import java.io.File
import java.net.URL
import kotlin.time.Duration

/** Every value shape the bridge carries, each as the bridge converts it. */
@ExpoModule(name = "Values")
class ValuesModule : Module() {
    @JS
    fun scalars(a: Int, b: Long, c: Float, d: Double, e: Boolean): Boolean = e

    @JS
    fun text(value: String): String = value

    // Only the nullable parameters after the last non-nullable one can be left out.
    @JS
    fun optionals(first: String?, second: Int, third: String?, fourth: Double?): String? = first

    @JS
    fun arrays(ints: IntArray, longs: LongArray, doubles: DoubleArray, flags: BooleanArray): FloatArray =
        FloatArray(0)

    // A ByteArray crosses as an ArrayBuffer, both ways.
    @JS
    fun bytes(data: ByteArray): ByteArray = data

    @JS
    fun containers(list: List<String>, set: Set<Int>, array: Array<Double?>, map: Map<String, List<Int>>): Int = 0

    @JS
    fun dynamic(value: Any?, handle: JavaScriptValue, obj: JavaScriptObject): Any? = value

    @JS
    fun typed(view: TypedArray): Int = 0

    @JS
    fun converted(timeout: Duration, file: File, url: URL?): Duration = timeout

    @JS
    fun nothing() = Unit

    @JS
    suspend fun later(name: String): String = name

    @JS
    suspend fun laterUnit() = Unit

    @JS
    fun promised(value: Int): Promise<Int> = Promise<Int>().apply { resolve(value) }

    @JS(name = "renamed")
    fun original(): Int = 0

    @JS
    val version: Int = 3

    @JS
    @Constant
    val tags: List<String> = listOf("a")

    @JS
    var volume: Double = 1.0

    @JS
    val computed: String
        get() = "now"

    @Event
    val onChanged = event<String>()

    @Event(name = "tick")
    val onTimer = event<Int>()

    @Event
    val onPing = event<Unit>()

    // Not exported.
    fun helper(): Int = 0
}

fun box(): String = "OK"
