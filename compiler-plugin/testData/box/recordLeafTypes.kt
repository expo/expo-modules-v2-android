// DUMP_IR
// FIR_DUMP

package expo.modules.v2.testdata

import expo.modules.v2.annotations.Record
import expo.modules.v2.records.codecFor
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// `Duration` is a value class, so its getter returns an unboxed long after JvmInlineClassLowering
// and the descriptor-driven `write(Any?, TypeDescriptor)` has to box it back.
@Record(bufferSafe = false)
class Leaves(
    val duration: Duration,
    val file: File,
    val url: URL,
    val uri: URI,
    val path: Path,
    val maybe: Duration?,
)

fun box(): String {
    val codec = codecFor<Leaves>()

    val fieldTypes = codec.schema.fields.map { "${it.name}=${it.type}" }
    val expected = listOf(
        "duration=Duration", "file=File", "url=URL", "uri=URI", "path=Path", "maybe=Duration?",
    )
    if (fieldTypes != expected) return "Fail: descriptors are $fieldTypes"

    val leaves = Leaves(
        duration = 3.seconds,
        file = File("/tmp/a"),
        url = URL("https://example.com/a"),
        uri = URI("https://example.com/b"),
        path = Paths.get("/tmp/b"),
        maybe = null,
    )

    val map = codec.toMap(leaves)
    if (map["duration"] != 3.0) return "Fail: duration crossed as ${map["duration"]}"
    if (map["file"] != "/tmp/a") return "Fail: file crossed as ${map["file"]}"
    if (map["maybe"] != null) return "Fail: maybe crossed as ${map["maybe"]}"

    val back = codec.fromMap(map)
    if (back.duration != leaves.duration) return "Fail: duration gave ${back.duration}"
    if (back.file != leaves.file) return "Fail: file gave ${back.file}"
    if (back.url != leaves.url) return "Fail: url gave ${back.url}"
    if (back.uri != leaves.uri) return "Fail: uri gave ${back.uri}"
    if (back.path != leaves.path) return "Fail: path gave ${back.path}"
    if (back.maybe != null) return "Fail: maybe gave ${back.maybe}"

    return "OK"
}
