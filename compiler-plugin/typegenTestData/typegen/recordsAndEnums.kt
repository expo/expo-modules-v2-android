package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Enumerable
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.Record

/** Crosses by its entry names. */
enum class Direction : Enumerable {
    UP,
    DOWN,
}

/** Crosses by its one `String` property. */
enum class Style(val value: String) : Enumerable {
    LIGHT("light"),
    HEAVY("heavy"),
}

/** Crosses by its one `Int` property. */
enum class Level(val value: Int) : Enumerable {
    LOW(1),
    HIGH(10),
}

/** Not marked `Enumerable`, but an export names it, so it is described all the same. */
enum class Unmarked {
    ONE,
}

@Record
data class Point(val x: Double, val y: Double)

/** Defaults and nullability decide what JavaScript must supply. */
@Record
data class Options(
    val name: String,
    val quality: Int = 80,
    val label: String? = null,
    val style: Style = Style.LIGHT,
    val tags: List<String> = emptyList(),
    val origin: Point?,
)

/** A record that names itself. */
@Record
data class Node(val value: Int, val children: List<Node>)

@ExpoModule
class Shapes : Module() {
    @JS
    fun move(point: Point, direction: Direction, level: Level?): Point = point

    @JS
    fun configure(options: Options): Options = options

    @JS
    fun tree(): Node = Node(0, emptyList())

    @JS
    fun unmarked(value: Unmarked): Unmarked = value

    @JS
    fun byStyle(): Map<String, Style> = emptyMap()
}

fun box(): String = "OK"
