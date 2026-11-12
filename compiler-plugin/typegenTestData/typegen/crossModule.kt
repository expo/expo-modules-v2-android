// MODULE: producer
// FILE: producer.kt
package io.github.expo.modules.v2.testdata.producer

import io.github.expo.modules.v2.Enumerable
import io.github.expo.modules.v2.Record

// Compiled in its own module, so the consumer sees these only through its class files.
@Record
data class Size(val width: Int, val height: Int = 0, val unit: Dimension?)

enum class Dimension : Enumerable {
    PX,
    DP,
}

// Its entries' arguments are not in the class files, so the consumer cannot describe it.
enum class Mode(val value: String) : Enumerable {
    FAST("fast"),
}

// MODULE: consumer(producer)
// FILE: consumer.kt
package io.github.expo.modules.v2.testdata.consumer

import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.testdata.producer.Mode
import io.github.expo.modules.v2.testdata.producer.Size

@ExpoModule
class Layout : Module() {
    @JS
    fun measure(size: Size): Size = size

    @JS
    fun mode(mode: Mode): Mode = mode
}

fun box(): String = "OK"
