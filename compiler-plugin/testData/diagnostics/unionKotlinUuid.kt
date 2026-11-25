// RUN_PIPELINE_TILL: FRONTEND
@file:OptIn(ExperimentalUuidApi::class)

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Union
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// A string Uuid.parse refuses is a String.
@Union
sealed interface IdFirst {
    @JvmInline
    value class Id(val id: Uuid) : IdFirst

    @JvmInline
    value class Name(val name: String) : IdFirst
}

// A String takes every string, so a Uuid after it is never picked.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface NameFirst<!> {
    @JvmInline
    value class Name(val name: String) : NameFirst

    @JvmInline
    value class Id(val id: Uuid) : NameFirst
}

// A Uuid and a number are told apart by the kind of JavaScript value.
@Union
sealed interface IdOrIndex {
    @JvmInline
    value class Id(val id: Uuid) : IdOrIndex

    @JvmInline
    value class Index(val index: Int) : IdOrIndex
}
