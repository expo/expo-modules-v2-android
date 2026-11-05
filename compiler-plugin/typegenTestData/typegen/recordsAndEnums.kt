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

/** An entry with a body passes its value from its own class's constructor. */
enum class Shape(val value: String) : Enumerable {
    ROUND("round") {
        override fun corners(): Int = 0
    },
    SQUARE("square") {
        override fun corners(): Int = 4
    };

    abstract fun corners(): Int
}

/** An entry that passes nothing takes the parameter's default. */
enum class Priority(val value: Int = 5) : Enumerable {
    NORMAL,
    URGENT(9),
}

/** A `String?` entry that holds null crosses by its name. */
enum class Alias(val value: String?) : Enumerable {
    FIRST("first"),
    SECOND(null),
}

/** An entry that calls another constructor: its value is computed, so the enum is not described. */
enum class Answer(val value: String) : Enumerable {
    YES(true);

    constructor(flag: Boolean) : this(if (flag) "yes" else "no")
}

interface Crossing : Enumerable

/** `Enumerable` through another interface. */
enum class Indirect : Crossing {
    ONLY,
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

    @JS
    fun more(shape: Shape, priority: Priority, alias: Alias): Indirect = Indirect.ONLY

    @JS
    fun answer(): Answer = Answer.YES
}

fun box(): String = "OK"
