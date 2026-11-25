// RUN_PIPELINE_TILL: FRONTEND

// FILE: Bundle.kt
// A stand-in for the Android framework class: these tests run on the plain JVM, and only the class's
// name decides how a variant of it crosses.
package android.os

class Bundle

// FILE: unions.kt
package io.github.expo.modules.v2.testdata

import android.os.Bundle
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.Union

@Record
data class Point(val x: Double, val y: Double)

class Thing : SharedObject()

// A Bundle takes every object, so a map after it is never picked.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface BundleFirst<!> {
    @JvmInline
    value class Extras(val extras: Bundle) : BundleFirst

    @JvmInline
    value class Counts(val counts: Map<String, Int>) : BundleFirst
}

// Nor is a record.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface BundleBeforeRecord<!> {
    @JvmInline
    value class Extras(val extras: Bundle) : BundleBeforeRecord

    @JvmInline
    value class At(val point: Point) : BundleBeforeRecord
}

// A map of nullable dynamic values takes every object a Bundle would.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface DynamicMapFirst<!> {
    @JvmInline
    value class Values(val values: Map<String, Any?>) : DynamicMapFirst

    @JvmInline
    value class Extras(val extras: Bundle) : DynamicMapFirst
}

// A non-null Any refuses a null value and passes the object on, so the Bundle still gets
// `{ x: null }`.
@Union
sealed interface NonNullDynamicMapFirst {
    @JvmInline
    value class Values(val values: Map<String, Any>) : NonNullDynamicMapFirst

    @JvmInline
    value class Extras(val extras: Bundle) : NonNullDynamicMapFirst
}

// A Bundle takes an ArrayBuffer too - as an empty Bundle - so a ByteArray after it is never picked.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface BundleBeforeBytes<!> {
    @JvmInline
    value class Extras(val extras: Bundle) : BundleBeforeBytes

    @JvmInline
    value class Data(val data: ByteArray) : BundleBeforeBytes
}

// And a shared object, as its enumerable properties.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface BundleBeforeShared<!> {
    @JvmInline
    value class Extras(val extras: Bundle) : BundleBeforeShared

    @JvmInline
    value class Handle(val handle: Thing) : BundleBeforeShared
}

// Declared first, the ByteArray and the shared object get theirs.
@Union
sealed interface BytesAndSharedFirst {
    @JvmInline
    value class Data(val data: ByteArray) : BytesAndSharedFirst

    @JvmInline
    value class Handle(val handle: Thing) : BytesAndSharedFirst

    @JvmInline
    value class Extras(val extras: Bundle) : BytesAndSharedFirst
}

// An object Map<String, Int> refuses still reaches the Bundle.
@Union
sealed interface CountsFirst {
    @JvmInline
    value class Counts(val counts: Map<String, Int>) : CountsFirst

    @JvmInline
    value class Extras(val extras: Bundle) : CountsFirst
}

// So does one the record refuses.
@Union
sealed interface RecordFirst {
    @JvmInline
    value class At(val point: Point) : RecordFirst

    @JvmInline
    value class Extras(val extras: Bundle) : RecordFirst
}

// An object and a number are told apart by the kind of JavaScript value.
@Union
sealed interface BundleOrCount {
    @JvmInline
    value class Extras(val extras: Bundle) : BundleOrCount

    @JvmInline
    value class Count(val count: Int) : BundleOrCount
}
