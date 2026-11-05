package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Event
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.ExpoSharedObject
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.SharedRef

/** A constructor JavaScript can call, a property, and an event. */
@ExpoSharedObject
class Player(private val url: String, private val volume: Double?) : SharedObject() {
    @JS
    fun play(): Boolean = true

    @JS
    var looping: Boolean = false

    @Event
    val onStatus = event<String>()
}

/** Two constructors and none marked `@JS`: JavaScript cannot construct it, so no module lists it. */
@ExpoSharedObject(name = "Speaker")
class SpeakerObject private constructor(private val id: Int) : SharedObject() {
    constructor() : this(0)

    @JS
    fun mute() = Unit
}

@ExpoSharedObject
class TextRef(text: StringBuilder) : SharedRef<StringBuilder>(text) {
    @JS
    fun length(): Int = ref.length
}

@ExpoModule(classes = [Player::class])
class Media : Module() {
    @ExpoSharedObject
    class Track(private val title: String) : SharedObject() {
        @JS
        fun title(): String = title
    }

    @JS
    fun current(): Player? = null

    @JS
    fun tracks(): List<Track> = emptyList()

    @JS
    fun wrap(text: TextRef): Int = 0

    @JS
    fun raw(ref: SharedRef<StringBuilder>): Int = 0

    @Event
    val onTrack = event<Track>()
}

fun box(): String = "OK"
