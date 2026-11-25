// RUN_PIPELINE_TILL: FRONTEND

// FILE: Color.kt
// A stand-in for the Android framework class: these tests run on the plain JVM, and only the class's
// name decides how a variant of it crosses.
package android.graphics

open class Color

// FILE: unions.kt
package io.github.expo.modules.v2.testdata

import android.graphics.Color
import io.github.expo.modules.v2.Union

// A Color crosses as a dynamic value, so native code offers it every value: a variant after it is
// never picked.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface ColorFirst<!> {
    @JvmInline
    value class Tint(val color: Color) : ColorFirst

    @JvmInline
    value class Text(val text: String) : ColorFirst
}

// Declared last, it takes what the variants before it do not.
@Union
sealed interface ColorLast {
    @JvmInline
    value class Text(val text: String) : ColorLast

    @JvmInline
    value class Count(val count: Int) : ColorLast

    @JvmInline
    value class Tint(val color: Color) : ColorLast
}
