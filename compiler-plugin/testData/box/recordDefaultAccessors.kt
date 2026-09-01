// DUMP_IR
// FIR_DUMP

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.annotations.Record
import io.github.expo.modules.v2.records.codecFor

// Private in the file, so the JVM sees a private static on the file facade — the generated `decode`
// lives in another class and needs a synthetic accessor to call it.
private fun secret(): Int = 11

private const val SECRET_CONSTANT: String = "const"

@Record
class Accessors(
    val fromPrivateTopLevel: Int = secret(),
    val fromPrivateConstant: String = SECRET_CONSTANT,
    val fromCompanion: Int = Accessors.companionDefault,
    val fromPrivateCompanion: Int = privateCompanionDefault,
) {
    companion object {
        val companionDefault = 22
        private const val privateCompanionDefault = 33
    }
}

fun box(): String {
    val decoded = codecFor<Accessors>().fromMap(emptyMap())
    if (decoded.fromPrivateTopLevel != 11) return "Fail: private top-level gave ${decoded.fromPrivateTopLevel}"
    if (decoded.fromPrivateConstant != "const") return "Fail: private const gave ${decoded.fromPrivateConstant}"
    if (decoded.fromCompanion != 22) return "Fail: companion gave ${decoded.fromCompanion}"
    if (decoded.fromPrivateCompanion != 33) return "Fail: private companion gave ${decoded.fromPrivateCompanion}"
    return "OK"
}
