// RUN_PIPELINE_TILL: FRONTEND
@file:OptIn(ExperimentalUuidApi::class)

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.Union
import java.net.URI
import java.nio.file.Path
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

// URI.create takes every spelling Uuid.parse does, so a Uuid after a URI is never picked.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface LinkFirst<!> {
    @JvmInline
    value class Link(val uri: URI) : LinkFirst

    @JvmInline
    value class Id(val id: Uuid) : LinkFirst
}

// Neither does Paths.get refuse one, so a Uuid after a Path is never picked either.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface PathFirst<!> {
    @JvmInline
    value class Location(val path: Path) : PathFirst

    @JvmInline
    value class Id(val id: Uuid) : PathFirst
}

// The other way round each is picked: a URI that is no UUID goes to the URI.
@Union
sealed interface IdThenLink {
    @JvmInline
    value class Id(val id: Uuid) : IdThenLink

    @JvmInline
    value class Link(val uri: URI) : IdThenLink
}

// The same holds inside a list: every array of UUIDs is an array of URIs.
<!UNION_UNREACHABLE_VARIANT!>@Union
sealed interface LinkListFirst<!> {
    @JvmInline
    value class Links(val uris: List<URI>) : LinkListFirst

    @JvmInline
    value class Ids(val ids: List<Uuid>) : LinkListFirst
}

// A Uuid and a number are told apart by the kind of JavaScript value.
@Union
sealed interface IdOrIndex {
    @JvmInline
    value class Id(val id: Uuid) : IdOrIndex

    @JvmInline
    value class Index(val index: Int) : IdOrIndex
}
