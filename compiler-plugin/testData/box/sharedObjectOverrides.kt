// FIR_IDENTICAL
// DUMP_IR

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.SharedRef

/**
 * `ExpoSharedObject`'s open members stay overridable when the plugin is the one that put the base class
 * there. The overrides are called through a base-typed reference, so what is checked is real virtual
 * dispatch and not just that the source compiled.
 */
@ExpoSharedObject
class Counter : SharedObject() {
    var released: Int = 0
        private set

    override fun sharedObjectDidRelease() {
        released++
    }

    override fun getAdditionalMemoryPressure(): Int = 42

    @JS
    fun value(): Int = released
}

/** An override alongside an exported member of the same name shape, to catch a clash. */
@ExpoSharedObject
class Pressured : SharedObject() {
    override fun getAdditionalMemoryPressure(): Int = 7
}

/**
 * A `super` call reaches the base the plugin installed. This is the case the backend half of the
 * supertype swap could get wrong: it emits `invokespecial` naming the superclass directly.
 */
@ExpoSharedObject
class Delegating : SharedObject() {
    var released: Int = 0
        private set

    override fun sharedObjectDidRelease() {
        super.sharedObjectDidRelease()
        released++
    }

    override fun getAdditionalMemoryPressure(): Int = super.getAdditionalMemoryPressure() + 5
}

/** The declared-base case, for comparison: nothing about it changed. */
@ExpoSharedObject
class TextRef(text: StringBuilder) : SharedRef<StringBuilder>(text) {
    override fun getAdditionalMemoryPressure(): Int = ref.length
}

/** No override at all: the inherited defaults still answer. */
@ExpoSharedObject
class Plain : SharedObject()

fun box(): String {
    val base: io.github.expo.modules.v2.SharedObject = Counter()

    if (base.getAdditionalMemoryPressure() != 42) {
        return "Counter.getAdditionalMemoryPressure: ${base.getAdditionalMemoryPressure()}"
    }
    base.sharedObjectDidRelease()
    base.sharedObjectDidRelease()
    if ((base as Counter).released != 2) {
        return "Counter.sharedObjectDidRelease ran ${base.released} times"
    }

    val pressured: io.github.expo.modules.v2.SharedObject = Pressured()
    if (pressured.getAdditionalMemoryPressure() != 7) {
        return "Pressured: ${pressured.getAdditionalMemoryPressure()}"
    }

    val delegating: io.github.expo.modules.v2.SharedObject = Delegating()
    if (delegating.getAdditionalMemoryPressure() != 5) {
        return "Delegating: ${delegating.getAdditionalMemoryPressure()}"
    }
    delegating.sharedObjectDidRelease()
    if ((delegating as Delegating).released != 1) {
        return "Delegating.sharedObjectDidRelease ran ${delegating.released} times"
    }

    val ref: io.github.expo.modules.v2.SharedObject = TextRef(StringBuilder("abcd"))
    if (ref.getAdditionalMemoryPressure() != 4) {
        return "TextRef: ${ref.getAdditionalMemoryPressure()}"
    }

    val plain: io.github.expo.modules.v2.SharedObject = Plain()
    if (plain.getAdditionalMemoryPressure() != 0) {
        return "Plain: ${plain.getAdditionalMemoryPressure()}"
    }
    plain.sharedObjectDidRelease()

    // The override has to land on the class itself, not be left as an inherited fake.
    val declared = Counter::class.java.declaredMethods.map { it.name }
    for (name in listOf("sharedObjectDidRelease", "getAdditionalMemoryPressure")) {
        if (name !in declared) return "Counter does not declare $name"
    }

    return "OK"
}
