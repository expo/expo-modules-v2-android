// RUN_PIPELINE_TILL: FRONTEND

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Event
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject

// The plugin wraps an `@Event` property's initializer, so only a plain `val ... = event<T>(...)`
// on an exported class can be one. The accepted shapes are collected at the bottom of this file.

// The container carries no @ExpoModule or @ExpoSharedObject.
class NotExported : Module() {
    @Event
    val <!EVENT_OUTSIDE_MODULE!>onChanged<!> = event<Int>()
}

@ExpoModule
class CompanionEvent : Module() {
    companion object {
        val other = Other()
        @Event
        val <!EVENT_OUTSIDE_MODULE!>onChanged<!> = other.onChanged
    }
}

@ExpoModule
class Other : Module() {
    @Event
    val onChanged = event<Int>()
}

// An event is subscribed to, never read, so it cannot also be a @JS value.
@ExpoModule
class BothAnnotations : Module() {
    @Event
    @JS
    val <!EVENT_WITH_JS!>onChanged<!> = event<Int>()
}

@ExpoModule
class BadShapes : Module() {
    @Event
    var <!EVENT_UNSUPPORTED_SHAPE!>onVar<!> = event<Int>()

    @Event
    lateinit var <!EVENT_UNSUPPORTED_SHAPE!>onLate<!>: io.github.expo.modules.v2.events.Event<Int>

    @Event
    val <!EVENT_UNSUPPORTED_SHAPE!>onDelegated<!> by lazy { event<Int>() }

    @Event
    val Int.<!EVENT_UNSUPPORTED_SHAPE!>onReceived<!>: io.github.expo.modules.v2.events.Event<Int>
        get() = event<Int>()

    @Event
    val <!EVENT_UNSUPPORTED_SHAPE!>onComputed<!>: io.github.expo.modules.v2.events.Event<Int>
        get() = event<Int>()

    @Event
    val <!EVENT_UNSUPPORTED_SHAPE!>onCustomGetter<!> = event<Int>()
        get() = field
}

@ExpoModule
class FromConstructor(
    @property:Event
    val <!EVENT_UNSUPPORTED_SHAPE!>onGiven<!>: io.github.expo.modules.v2.events.Event<Int>,
) : Module()

// The declared type is what `event<T>()` returns, non-null.
@ExpoModule
class WrongTypes : Module() {
    @Event
    val <!EVENT_WRONG_TYPE!>onNumber<!> = 1

    @Event
    val <!EVENT_WRONG_TYPE!>onNullable<!>: io.github.expo.modules.v2.events.Event<Int>? = event<Int>()
}

// `event<T>()` is what ties the event to this instance; anything else would emit on another one.
@ExpoModule
class Borrowed : Module() {
    val other = Other()

    @Event
    val <!EVENT_INITIALIZER_IS_NOT_EVENT_CALL!>onChanged<!> = other.onChanged

    @Event
    val <!EVENT_INITIALIZER_IS_NOT_EVENT_CALL!>onWrapped<!> = run { event<Int>() }
}

// Events share the export namespace with functions and properties, after the `on` prefix goes.
@ExpoModule
class Collisions : Module() {
    @Event
    val onChanged = event<Int>()

    @JS
    fun <!JS_DUPLICATE_EXPORT_NAME!>changed<!>(): Int = 1

    @Event(name = "count")
    val onCount = event<Int>()

    @JS
    val <!JS_DUPLICATE_EXPORT_NAME!>count<!>: Int = 1
}

// Every module and shared object carries the emitter's own members, so nothing may take their names.
@ExpoModule
class Reserved : Module() {
    @JS
    fun <!JS_RESERVED_EXPORT_NAME!>emit<!>(): Int = 1

    @JS
    val <!JS_RESERVED_EXPORT_NAME!>listenerCount<!>: Int = 0

    @Event(name = "addListener")
    val <!JS_RESERVED_EXPORT_NAME!>onAdd<!> = event<Int>()
}

@ExpoSharedObject
class ReservedShared : SharedObject() {
    @JS
    fun <!JS_RESERVED_EXPORT_NAME!>removeAllListeners<!>(): Int = 1
}

// Accepted shapes: none of these report.
@ExpoModule
class GoodEvents : Module() {
    @Event
    val onChanged = event<Int>()

    @Event(name = "renamed")
    val onSomething = event<String>(onStartObserving = {}, onStopObserving = {})

    @Event
    val progress = event<Double>()

    // A non-exported Event property is left alone, whatever its shape.
    var notExported = event<Int>()
}

@ExpoModule
object GoodObjectEvents : Module() {
    @Event
    val onTick = event<Int>()
}

@ExpoSharedObject
class GoodSharedEvents : SharedObject() {
    @Event
    val onStateChange = event<String>()

    @JS
    fun play(): Int = 1
}
