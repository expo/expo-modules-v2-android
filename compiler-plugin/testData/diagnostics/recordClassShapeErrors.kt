// RUN_PIPELINE_TILL: FRONTEND
// FIR_DUMP

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.annotations.Record
import io.github.expo.modules.v2.records.Record as RecordMarker
import io.github.expo.modules.v2.records.RecordCodec
import io.github.expo.modules.v2.records.RecordSchema
import io.github.expo.modules.v2.records.readers.RecordReader
import io.github.expo.modules.v2.records.writers.RecordWriter

<!RECORD_ON_UNSUPPORTED_DECLARATION!>@Record
interface NotAClass<!>

@Record
<!RECORD_ON_UNSUPPORTED_DECLARATION!>object NotAnObject<!>

@Record
<!RECORD_ON_UNSUPPORTED_DECLARATION!>enum class NotAnEnum<!> { A }

@Record
annotation <!RECORD_ON_UNSUPPORTED_DECLARATION!>class NotAnAnnotation<!>

@Record
abstract <!RECORD_ON_UNSUPPORTED_DECLARATION!>class NotAbstract<!>(val a: Int)

@Record
sealed <!RECORD_ON_UNSUPPORTED_DECLARATION!>class NotSealed<!>

@Record
<!RECORD_WITH_TYPE_PARAMETERS!>class Generic<!><T>(val a: Int)

class Outer {
    @Record
    inner <!RECORD_ON_UNSUPPORTED_DECLARATION!>class NotInner<!>(val a: Int)
}

@Record
<!RECORD_WITHOUT_PRIMARY_CONSTRUCTOR!>class NoPrimaryConstructor<!> {
    constructor(a: Int)
}

@Record
class NotAProperty(<!RECORD_PARAMETER_IS_NOT_A_PROPERTY!>a: Int<!>, val b: Int)

// A hand-written codec would register the class a second time.
@Record
<!RECORD_DECLARES_ITS_OWN_CODEC!>class OwnCodec<!>(val a: Int) : RecordMarker {
    companion object : RecordCodec<OwnCodec> {
        override val recordClass = OwnCodec::class.java
        override val schema = RecordSchema("OwnCodec", true)
        override fun encode(value: OwnCodec, writer: RecordWriter) = Unit
        override fun decode(reader: RecordReader) = OwnCodec(0)
    }
}
