// FIR_IDENTICAL

// FILE: Color.kt
// A stand-in for the Android framework class: box tests run on the plain JVM, and only the class's
// name decides how it crosses.
package android.graphics

open class Color

// FILE: module.kt
package io.github.expo.modules.v2.testdata

import android.graphics.Color
import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.Union

/** Compiled only: a record codec has to take a `Color` field and a nullable one. */
@Record
class Theme(val tint: Color, val accent: Color?)

/** Compiled only: a union codec with a `Color` variant, declared last as the checker asks. */
@Union
sealed interface Paint {
    @JvmInline
    value class Text(val text: String) : Paint

    @JvmInline
    value class Tint(val color: Color) : Paint
}

/**
 * `android.graphics.Color` crosses as a dynamic value, like `Any`: in its own slot unless told to
 * ride the buffer, and never on it inside a container or a record.
 *
 * Only the generated trampolines are checked. `assertExports` runs `define$ExpoModulesV2`, which
 * resolves each converter, and the one for `Color` is registered by `:react` - this runs against
 * `:api`'s desktop table, which has none.
 */
@ExpoModule
object AndroidColors : Module() {
    @JS
    fun tint(color: Color): Color = color

    @JS
    fun maybe(color: Color?): Color? = color

    @JS
    @BufferMode(Buffer.YES)
    fun buffered(color: Color): Color = color

    @JS
    fun palette(colors: List<Color>): List<Color> = colors

    @JS
    fun apply(theme: Theme): Color = theme.tint

    @JS
    fun paint(paint: Paint): String = paint.toString()
}

private fun descriptorOf(owner: Class<*>, name: String): String {
    val method = owner.declaredMethods.firstOrNull { it.name == name }
        ?: return "<no method $name>"
    val parameters = method.parameterTypes.joinToString("") { descriptor(it) }
    return "($parameters)${descriptor(method.returnType)}"
}

private fun descriptor(type: Class<*>): String = when {
    type == Int::class.javaPrimitiveType -> "I"
    type == Void.TYPE -> "V"
    else -> "L" + type.name.replace('.', '/') + ";"
}

fun box(): String {
    val suffix = "__trampoline\$ExpoModulesV2"
    val expected = mapOf(
        // the bridge value is whatever JavaScript passed, so the slot is an Object each way
        "tint$suffix" to "(Ljava/lang/Object;)Ljava/lang/Object;",
        "maybe$suffix" to "(Ljava/lang/Object;)Ljava/lang/Object;",
        // Buffer.YES: the tagged dynamic codec, one payloadLength in, the result's length out
        "buffered$suffix" to "(I)I",
        // a dynamic element keeps its container off the buffer
        "palette$suffix" to "(Ljava/util/List;)Ljava/util/List;",
        // and a record holding one crosses as a map
        "apply$suffix" to "(Ljava/util/Map;)Ljava/lang/Object;",
        // a union with a dynamic variant keeps its slot too
        "paint$suffix" to "(Lio/github/expo/modules/v2/unions/UnionValue;)Ljava/lang/String;",
    )
    for ((name, want) in expected) {
        val got = descriptorOf(AndroidColors::class.java, name)
        if (got != want) return "$name: expected $want, got $got"
    }

    return "OK"
}
