// FIR_IDENTICAL
// DUMP_IR
// FIR_DUMP

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.Tag
import io.github.expo.modules.v2.Union
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.anyConverter
import io.github.expo.modules.v2.unions.UnionValue
import io.github.expo.modules.v2.unions.Union as UnionMarker

// A variant of each form: a value class, an object, and a record.
@Union
sealed interface Dimension {
    @JvmInline
    value class Points(val value: Double) : Dimension

    @Tag("auto")
    data object Auto : Dimension

    @Record
    data class Percent(val percent: Double) : Dimension
}

// A sealed class with a user companion the plugin has to extend rather than create, and records
// told apart by a discriminator.
@Union(discriminator = "type")
sealed class Shape {
    @Record
    @Tag("circle")
    data class Circle(val radius: Double) : Shape()

    @Record
    data class Rect(val width: Double, val height: Double) : Shape()

    companion object {
        const val LABEL = "shape"
    }
}

// Variants declared outside the union, and a plain class holding a String.
@Union
sealed interface Id

class Text(val value: String) : Id

data class Count(val value: Int) : Id

private fun converterOf(type: Class<*>) = TypeDescriptor.Simple(type, false).anyConverter

fun box(): String {
    if (Dimension.Auto !is UnionMarker) return "Fail: Dimension lost the marker"

    val dimension = converterOf(Dimension::class.java)
    val points = dimension.toJni(Dimension.Points(2.5)) as UnionValue
    if (points.index != 0 || points.payload != 2.5) return "Fail: Points crossed as ${points.index}/${points.payload}"
    val auto = dimension.toJni(Dimension.Auto) as UnionValue
    if (auto.index != 1 || auto.payload != null) return "Fail: Auto crossed as ${auto.index}/${auto.payload}"
    val percent = dimension.toJni(Dimension.Percent(0.5)) as UnionValue
    if (percent.index != 2 || percent.payload != mapOf("percent" to 0.5)) return "Fail: Percent crossed as ${percent.payload}"

    if (dimension.fromJni(UnionValue(0, 3.0)) != Dimension.Points(3.0)) return "Fail: Points round trip"
    if (dimension.fromJni(UnionValue(1, null)) != Dimension.Auto) return "Fail: Auto round trip"
    if (dimension.fromJni(UnionValue(2, mapOf("percent" to 1.0))) != Dimension.Percent(1.0)) return "Fail: Percent round trip"

    val badIndex = runCatching { dimension.fromJni(UnionValue(3, null)) }.exceptionOrNull()?.message
    if (badIndex != "Dimension has no variant #3") return "Fail: an unknown index threw '$badIndex'"

    if (Shape.LABEL != "shape") return "Fail: user companion member disappeared"
    val shape = converterOf(Shape::class.java)
    if (shape.fromJni(UnionValue(1, mapOf("width" to 2.0, "height" to 3.0))) != Shape.Rect(2.0, 3.0)) {
        return "Fail: Rect round trip"
    }

    val id = converterOf(Id::class.java)
    val text = id.toJni(Text("a")) as UnionValue
    if (text.index != 0 || text.payload != "a") return "Fail: Text crossed as ${text.index}/${text.payload}"
    if (id.fromJni(UnionValue(1, 7)) != Count(7)) return "Fail: Count round trip"

    return "OK"
}
