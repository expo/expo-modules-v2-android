// RUN_PIPELINE_TILL: FRONTEND
// FIR_DUMP

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.Tag
import io.github.expo.modules.v2.Union
import java.net.URL

<!UNION_ON_UNSUPPORTED_DECLARATION!>@Union
interface NotSealed<!>

@Union
<!UNION_ON_UNSUPPORTED_DECLARATION!>enum class NotAnEnum<!> { A }

@Union
<!UNION_ON_UNSUPPORTED_DECLARATION!>object NotAnObject<!>

<!UNION_WITH_TYPE_PARAMETERS!>@Union
sealed interface Generic<!><T> {
    <!UNION_UNSUPPORTED_VARIANT!>class Value<!><T>(val value: T) : Generic<T>
}

<!UNION_WITHOUT_VARIANTS!>@Union
sealed interface Empty<!>

// A whole number is an Int, any other number a Double.
@Union
sealed interface Numbers {
    @JvmInline
    value class Whole(val value: Int) : Numbers

    @JvmInline
    value class Fraction(val value: Double) : Numbers
}

// A Double takes every number, so an Int after it is never picked.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface FractionFirst<!> {
    @JvmInline
    value class Fraction(val value: Double) : FractionFirst

    @JvmInline
    value class Whole(val value: Int) : FractionFirst
}

// A Long takes every whole number an Int would.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface LongFirst<!> {
    @JvmInline
    value class Big(val value: Long) : LongFirst

    @JvmInline
    value class Small(val value: Int) : LongFirst
}

// A string URL refuses is a String.
@Union
sealed interface Link {
    @JvmInline
    value class Address(val url: URL) : Link

    @JvmInline
    value class Text(val text: String) : Link
}

// A String takes every string, so a URL after it is never picked.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface TextFirst<!> {
    @JvmInline
    value class Text(val text: String) : TextFirst

    @JvmInline
    value class Address(val url: URL) : TextFirst
}

// Records told apart by the fields they need.
@Union
sealed interface Figures {
    @Record
    data class Circle(val x: Double, val y: Double, val radius: Double) : Figures

    @Record
    data class Point(val x: Double, val y: Double, val label: String? = null) : Figures
}

// An object with a Circle's fields has a Point's too.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface PointFirst<!> {
    @Record
    data class Point(val x: Double, val y: Double) : PointFirst

    @Record
    data class Circle(val x: Double, val y: Double, val radius: Double) : PointFirst
}

// A Map takes every object.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface MapFirst<!> {
    class Values(val values: Map<String, Int>) : MapFirst

    @Record
    data class Point(val x: Double, val y: Double) : MapFirst
}

// Two objects that cross as the same string.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface SameLiteral<!> {
    @Tag("auto")
    data object First : SameLiteral

    @Tag("auto")
    data object Second : SameLiteral
}

// A String takes "auto" too.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface LiteralAfterText<!> {
    @JvmInline
    value class Text(val text: String) : LiteralAfterText

    @Tag("auto")
    data object Auto : LiteralAfterText
}

// Two records with the same tag.
<!UNION_UNREACHABLE_VARIANT!>@Union(discriminator = "type")
sealed interface SameTag<!> {
    @Record
    @Tag("shape")
    data class Circle(val radius: Double) : SameTag

    @Record
    @Tag("shape")
    data class Square(val side: Double) : SameTag
}

open class Base : SharedObject()

class Derived : Base()

// A subclass first: a Base variant takes every Derived.
@Union
sealed interface SubclassFirst {
    class OfDerived(val value: Derived) : SubclassFirst

    class OfBase(val value: Base) : SubclassFirst
}

<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface SuperclassFirst<!> {
    class OfBase(val value: Base) : SuperclassFirst

    class OfDerived(val value: Derived) : SuperclassFirst
}

@Union
sealed interface Variants {
    <!UNION_UNSUPPORTED_VARIANT!>class TwoProperties<!>(val a: Int, val b: Int) : Variants

    <!UNION_UNSUPPORTED_VARIANT!>class NotAProperty<!>(a: Int) : Variants

    open <!UNION_UNSUPPORTED_VARIANT!>class Open<!>(val a: String) : Variants

    sealed <!UNION_UNSUPPORTED_VARIANT!>class Nested<!> : Variants

    <!UNION_UNSUPPORTED_VARIANT!>class Hidden<!>(private val a: Boolean) : Variants

    <!UNION_UNSUPPORTED_VARIANT!>class HoldsAUnion<!>(val numbers: Numbers) : Variants

    <!UNION_VARIANT_WITH_NULLABLE_VALUE!>class Nullable<!>(val a: List<Int>?) : Variants
}

@Union(discriminator = "kind")
sealed interface Tagged {
    <!UNION_VARIANT_IS_NOT_A_RECORD!>class Untyped<!>(val values: Map<String, Int>) : Tagged

    @Record
    data <!UNION_VARIANT_SHADOWS_DISCRIMINATOR!>class Kinded<!>(val kind: String) : Tagged
}

enum class Priority(val value: Int) { LOW(0), HIGH(1) }

enum class Style { LIGHT, HEAVY }

// An Int-backed enum takes only its entries, so an Int after it still gets the rest.
@Union
sealed interface EnumThenInt {
    class Level(val priority: Priority) : EnumThenInt

    class Count(val count: Int) : EnumThenInt
}

// An Int takes every number an Int-backed enum would.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface IntThenEnum<!> {
    class Count(val count: Int) : IntThenEnum

    class Level(val priority: Priority) : IntThenEnum
}

// A String takes every name an enum would.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface TextThenEnum<!> {
    class Text(val text: String) : TextThenEnum

    class Named(val style: Style) : TextThenEnum
}
