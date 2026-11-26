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

// A Color crosses as a dynamic value, so native code offers it every value: a String after it is
// never picked, because a string the Color refuses cannot cross over to other type codes.
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

// But an Any after it is: it crosses with the Color's own type codes, so it gets what the Color
// refuses.
@Union
sealed interface ColorThenAny {
    @JvmInline
    value class Tint(val color: Color) : ColorThenAny

    @JvmInline
    value class Other(val value: Any) : ColorThenAny
}

// The same goes for a record or a map after a Color: native code offers the object to the Color.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface ColorThenMap<!> {
    @JvmInline
    value class Tint(val color: Color) : ColorThenMap

    @JvmInline
    value class Values(val values: Map<String, Any>) : ColorThenMap
}

// An Any before a Color takes every value, the Color's included.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface AnyThenColor<!> {
    @JvmInline
    value class Other(val value: Any) : AnyThenColor

    @JvmInline
    value class Tint(val color: Color) : AnyThenColor
}
