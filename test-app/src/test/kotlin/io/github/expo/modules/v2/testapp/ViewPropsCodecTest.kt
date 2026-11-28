package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.CommonDescriptors
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.views.MountedViewProps
import io.github.expo.modules.v2.views.ViewPropSpec
import io.github.expo.modules.v2.views.ViewPropsDecoder
import io.github.expo.modules.v2.views.ViewPropsNatives
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class CardProps(
  val title: String = "Untitled",
  val radius: Double = 4.0,
  val tags: List<String> = emptyList(),
)

/** By hand, in the shape a `@ViewProps` class would generate. */
private object CardPropsDecoder : ViewPropsDecoder<CardProps>(
  listOf(
    ViewPropSpec("title", AnyType(CommonDescriptors.STRING)),
    ViewPropSpec("radius", AnyType(CommonDescriptors.DOUBLE_BOXED)),
    ViewPropSpec(
      "tags",
      AnyType(TypeDescriptor.Parametrized(List::class.java, false, arrayOf(CommonDescriptors.STRING))),
    ),
  ),
) {
  const val TITLE = 0
  const val RADIUS = 1
  const val TAGS = 2

  private val defaults = CardProps()

  override fun defaults() = defaults

  @Suppress("UNCHECKED_CAST")
  override fun apply(props: CardProps, indices: IntArray, values: Array<Any?>): CardProps {
    var title = props.title
    var radius = props.radius
    var tags = props.tags
    for (i in indices.indices) {
      val value = values[i]
      when (indices[i]) {
        TITLE -> title = value as String? ?: defaults.title
        RADIUS -> radius = value as Double? ?: defaults.radius
        TAGS -> tags = value as List<String>? ?: defaults.tags
      }
    }
    return CardProps(title, radius, tags)
  }
}

/**
 * PROTOTYPE. Runs JavaScript props objects through the native codec - what `cloneProps` runs on
 * Android - and mounts them the way the UI thread does, against Hermes and without React Native.
 *
 * An "owner" stands in for the Fabric props object that keeps encoded props alive.
 */
class ViewPropsCodecTest {
  companion object {
    init {
      ExpoHermes.ensureLoaded()
    }
  }

  private val runtime = HermesRuntime()
  private val codec = ViewPropsNatives.createCodec(CardPropsDecoder)
  private val owners = mutableListOf<Long>()

  @AfterTest
  fun tearDown() {
    owners.forEach(ViewPropsNatives::releaseOwner)
    ViewPropsNatives.destroyCodec(codec)
    runtime.close()
  }

  /** Commits [source] on top of [previous], as `cloneProps` does. 0 when no prop of the view changed. */
  private fun commit(source: String, previous: Long = 0): Long =
    ViewPropsNatives.encodeForTesting(codec, runtime.evaluate("($source)"), previous).also {
      if (it != 0L) owners += it
    }

  private fun mount(owner: Long, mounted: MountedViewProps<CardProps>?) =
    CardPropsDecoder.mount(ViewPropsNatives.handleOf(owner), mounted, ViewPropsNatives)

  @Test
  fun `a new view mounts the props React set over the defaults`() {
    val diff = mount(commit("{ title: 'Card', tags: ['a', 'b'] }"), null)!!

    assertEquals(CardProps(title = "Card", tags = listOf("a", "b")), diff.newProps)
    assertTrue(diff.isInitial)
    assertEquals(listOf("title", "radius", "tags"), diff.changedNames)
  }

  @Test
  fun `props that are not the view's own are not encoded`() {
    // Base view props such as `opacity` are React Native's; with none of the view's own, nothing is
    // encoded and the props keep what they had.
    assertEquals(0, commit("{ opacity: 0.5, testID: 'x' }"))
    val created = commit("{ title: 'Card' }")
    assertEquals(0, commit("{ opacity: 0.5 }", created))
  }

  @Test
  fun `a mount brings the changes of commits that never mounted`() {
    val first = commit("{ title: 'Card' }")
    val mounted = mount(first, null)!!.new
    val skipped = commit("{ title: 'New card' }", first)
    val latest = commit("{ radius: 8 }", skipped)

    val diff = mount(latest, mounted)!!

    assertEquals(CardProps(title = "New card", radius = 8.0), diff.newProps)
    assertTrue(diff.changed(CardPropsDecoder.TITLE))
    assertTrue(diff.changed("radius"))
    assertFalse(diff.changed(CardPropsDecoder.TAGS))
    assertEquals("Card", diff.oldProps?.title)
  }

  @Test
  fun `a mount reads only what changed since the mounted props`() {
    val first = commit("{ title: 'Card', tags: ['a'] }")
    val mounted = mount(first, null)!!.new

    // Mounting the same props again finds nothing to do.
    assertNull(mount(first, mounted))
    val diff = mount(commit("{ tags: ['a', 'b'] }", first), mounted)!!
    assertEquals(listOf("tags"), diff.changedNames)
  }

  @Test
  fun `null resets a prop to its default`() {
    val created = commit("{ title: 'Card', radius: 10 }")
    val mounted = mount(created, null)!!.new

    val diff = mount(commit("{ title: null }", created), mounted)!!

    assertEquals(CardProps(radius = 10.0), diff.newProps)
    assertEquals(listOf("title"), diff.changedNames)
  }

  @Test
  fun `a value that does not encode changes nothing`() {
    val created = commit("{ title: 'Card' }")

    assertEquals(0, commit("{ radius: 'not a number' }", created))
  }

  @Test
  fun `a handle stops resolving once its props are freed`() {
    val owner = commit("{ title: 'Card' }")
    val handle = ViewPropsNatives.handleOf(owner)
    val next = commit("{ title: 'Next' }", owner)
    assertNotEquals(handle, ViewPropsNatives.handleOf(next))

    // Fabric frees props a later commit replaced. Their mount item finds nothing and the view waits for
    // the later one, which reads every change since what the view has.
    owners -= owner
    ViewPropsNatives.releaseOwner(owner)

    assertNull(CardPropsDecoder.mount(handle, null, ViewPropsNatives))
    assertEquals("Next", mount(next, null)!!.newProps.title)
  }
}
