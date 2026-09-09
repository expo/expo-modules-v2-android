// DUMP_IR

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.Event
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.SharedObject

@Record
data class Change(val value: Int) : io.github.expo.modules.v2.records.Record

/**
 * An `@Event` property is exported twice over: `define$ExpoModulesV2` records it on the builder,
 * and its initializer is wrapped in `EventSupport.bind`, which gives the `Event` its JavaScript
 * name and payload type at construction. The descriptor is hoisted into a static `type$N` field,
 * shared with any trampoline of the same class.
 */
@ExpoModule
class Watcher : Module() {
    var starts = 0
    var stops = 0

    // `on` + upper-case letter is dropped: JavaScript subscribes to `changed`.
    @Event
    val onChanged = event<Change>(
        onStartObserving = { starts++ },
        onStopObserving = { stops++ },
    )

    // Named explicitly, so the property name plays no part.
    @Event(name = "renamed")
    val onSomething = event<Int>()

    // No `on` prefix: used as it is.
    @Event
    val progress = event<Double>()

    // `on` followed by a lower-case letter is not a prefix.
    @Event
    val online = event<String>()

    // A `List<Int>` payload rides the buffer by default; `Buffer.NO` pins it to a JNI slot.
    @Event
    val onBatch = event<List<Int>>()

    @Event
    @BufferMode(Buffer.NO)
    val onSlot = event<List<Int>>()

    // A converted argument keeps this class's trampoline, so `type$N` is numbered once for both.
    @JS
    fun describe(change: Change): String = "change:${change.value}"

    fun fire(change: Change) = onChanged(change)
}

@ExpoModule
object Singleton : Module() {
    @Event
    val onTick = event<Int>()
}

@ExpoSharedObject
class Player : SharedObject() {
    @Event
    val onStateChange = event<String>()

    @JS
    fun play(): Int = 1
}

fun box(): String {
    val watcher = Watcher()

    // Bound at construction: the name JavaScript subscribes to, derived by the plugin.
    val expected = mapOf(
        watcher.onChanged to "changed",
        watcher.onSomething to "renamed",
        watcher.progress to "progress",
        watcher.online to "online",
        watcher.onBatch to "batch",
        watcher.onSlot to "slot",
    )
    for ((event, name) in expected) {
        if (event.name != name) return "expected '$name', got '${event.name}'"
    }
    if (Singleton.onTick.name != "tick") return "object: ${Singleton.onTick.name}"
    if (Player().onStateChange.name != "stateChange") return "shared object: ${Player().onStateChange.name}"

    // Nothing observes a fresh instance, and emitting into the void is a no-op rather than an error.
    if (watcher.onChanged.isObserved) return "observed before any listener"
    watcher.fire(Change(1))
    watcher.onSomething.emit(2)
    if (watcher.starts != 0 || watcher.stops != 0) return "hooks ran without a listener"

    // One descriptor field per distinct type, numbered once across the trampoline and the events.
    val fields = Watcher::class.java.declaredFields.map { it.name }.filter { it.startsWith("type\$") }
    if (fields.sorted() != fields.indices.map { "type\$$it" }.sorted()) return "descriptor fields: $fields"

    // The wrap changes the value, not the shape: the property keeps its plain getter.
    Watcher::class.java.getDeclaredMethod("getOnChanged")
    if (Watcher::class.java.declaredMethods.any { it.name.startsWith("getOnChanged__trampoline") }) {
        return "an event must not get a trampoline"
    }

    return "OK"
}
