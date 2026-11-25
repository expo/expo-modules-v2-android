// RUN_PIPELINE_TILL: FRONTEND

// FILE: Uri.kt
// A stand-in for the Android framework class: these tests run on the plain JVM, and only the class's
// name decides how a variant of it crosses.
package android.net

class Uri private constructor(private val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(value: String): Uri = Uri(value)
    }
}

// FILE: unions.kt
package io.github.expo.modules.v2.testdata

import android.net.Uri
import io.github.expo.modules.v2.Union
import java.net.URL

// A string URL refuses is a Uri.
@Union
sealed interface Link {
    @JvmInline
    value class Address(val url: URL) : Link

    @JvmInline
    value class Location(val uri: Uri) : Link
}

// Uri.parse takes every string, so a String after a Uri is never picked.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface UriFirst<!> {
    @JvmInline
    value class Location(val uri: Uri) : UriFirst

    @JvmInline
    value class Text(val text: String) : UriFirst
}

// And a String takes every string, so a Uri after it is never picked either.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface TextFirst<!> {
    @JvmInline
    value class Text(val text: String) : TextFirst

    @JvmInline
    value class Location(val uri: Uri) : TextFirst
}

// A Uri and a number are told apart by the kind of JavaScript value.
@Union
sealed interface UriOrPort {
    @JvmInline
    value class Location(val uri: Uri) : UriOrPort

    @JvmInline
    value class Port(val port: Int) : UriOrPort
}
