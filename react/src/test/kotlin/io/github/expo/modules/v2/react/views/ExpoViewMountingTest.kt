@file:Suppress("DEPRECATION")

package io.github.expo.modules.v2.react.views

import com.facebook.react.bridge.BridgeReactContext
import com.facebook.react.bridge.CatalystInstance
import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.fabric.mounting.SurfaceMountingManager
import com.facebook.react.internal.featureflags.ReactNativeFeatureFlagsForTests
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerModule
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.CommonDescriptors
import io.github.expo.modules.v2.views.EncodedViewPropsReader
import io.github.expo.modules.v2.views.PropsDiff
import io.github.expo.modules.v2.views.ViewPropSpec
import io.github.expo.modules.v2.views.ViewPropsDecoder
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class CardProps(val title: String = "Untitled", val radius: Double = 4.0)

private object CardPropsDecoder : ViewPropsDecoder<CardProps>(
  listOf(
    ViewPropSpec("title", AnyType(CommonDescriptors.STRING)),
    ViewPropSpec("radius", AnyType(CommonDescriptors.DOUBLE_BOXED)),
  ),
) {
  private val defaults = CardProps()

  override fun defaults() = defaults

  override fun apply(props: CardProps, indices: IntArray, values: Array<Any?>): CardProps {
    var result = props
    for (i in indices.indices) {
      result = when (indices[i]) {
        0 -> result.copy(title = values[i] as String? ?: defaults.title)
        else -> result.copy(radius = values[i] as Double? ?: defaults.radius)
      }
    }
    return result
  }
}

/**
 * Plays the part of the C++ codec, which can't load on the JVM: each [commit] makes new encoded props
 * on top of the previous ones, under a new handle, and [readChanges] writes the layout
 * `EncodedViewProps::writeChanges` writes. The desktop suite pins that layout against the real C++.
 */
private class FakeEncodedProps(private val decoder: ViewPropsDecoder<*>) : EncodedViewPropsReader {
  private class Encoded(val values: Array<Any?>, val isSet: BooleanArray, val revisions: LongArray)

  private val live = mutableMapOf<Long, Encoded>()
  private var nextHandle = 1L
  private var nextRevision = 1L

  /** Returns the handle as a double, as `ReadableNativeMap` delivers it. */
  fun commit(previous: Double?, vararg props: Pair<String, Any?>): Double {
    val base = previous?.let { live.getValue(it.toLong()) }
    val size = decoder.props.size
    val encoded = Encoded(
      base?.values?.copyOf() ?: arrayOfNulls(size),
      base?.isSet?.copyOf() ?: BooleanArray(size),
      base?.revisions?.copyOf() ?: LongArray(size),
    )
    val revision = nextRevision++
    for ((name, value) in props) {
      val index = decoder.indexOf(name)
      encoded.values[index] = value
      encoded.isSet[index] = true
      encoded.revisions[index] = revision
    }
    return nextHandle++.also { live[it] = encoded }.toDouble()
  }

  /** Fabric freed the props that owned [handle]. */
  fun release(handle: Double) {
    live.remove(handle.toLong())
  }

  override fun readChanges(handle: Long, mountedRevisions: LongArray?): BinaryBuffer? {
    val encoded = live[handle] ?: return null
    val buf = BinaryBuffer.allocate(4096)
    val changed = encoded.revisions.indices.filter { encoded.revisions[it] != (mountedRevisions?.get(it) ?: 0L) }
    buf.putInt(changed.size)
    for (index in changed) {
      buf.putInt(index)
      buf.putLong(encoded.revisions[index])
      val value = encoded.values[index]
      if (value == null) {
        buf.putTag(ViewPropsDecoder.ENTRY_RESET)
      } else {
        buf.putTag(ViewPropsDecoder.ENTRY_VALUE)
        decoder.props[index].type.anyConverter.writeToBuffer(buf, value)
      }
    }
    buf.limit = buf.position
    buf.position = 0
    return buf
  }
}

private class CardView(context: ThemedReactContext) : ExpoNativeView<CardProps>(context) {
  val diffs = mutableListOf<PropsDiff<CardProps>>()

  override fun didUpdateProps(diff: PropsDiff<CardProps>) {
    diffs += diff
  }
}

/**
 * PROTOTYPE. Runs React Native's own Java mounting layer (`MountingManager`, `SurfaceMountingManager`)
 * against [ExpoViewManager], the way RN's `SurfaceMountingManagerTest` does. The props maps are what
 * `ExpoViewComponentDescriptor::cloneProps` leaves in the raw props: base view props, plus the handle
 * of the view's encoded props under [ExpoViewManager.ENCODED_PROPS_KEY].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExpoViewMountingTest {
  private val surfaceId = 1
  private val tag = 42
  private val name = "ViewManagerAdapter_Cards_CardView"
  private val key = ExpoViewManager.ENCODED_PROPS_KEY

  private val encoded = FakeEncodedProps(CardPropsDecoder)
  private lateinit var surface: SurfaceMountingManager

  @Before
  fun setUp() {
    ReactNativeFeatureFlagsForTests.setUp()
    val reactContext = BridgeReactContext(RuntimeEnvironment.getApplication()).apply {
      initializeWithInstance(mockCatalystInstance())
    }
    val themedContext = ThemedReactContext(reactContext, reactContext, null, surfaceId)
    val manager = ExpoViewManager(ExpoViewDefinition(name, CardPropsDecoder, ::CardView, encoded))
    surface = MountingHarness.startSurface(manager, themedContext, surfaceId)
  }

  private fun view() = surface.getView(tag) as CardView

  private fun create(props: JavaOnlyMap) = surface.preallocateView(name, tag, props, null, true)

  @Test
  fun `a created view mounts its encoded props with the base view props`() {
    val handle = encoded.commit(null, "title" to "Card")

    create(JavaOnlyMap.of("opacity", 0.5, key, handle))

    val diff = view().diffs.single()
    assertTrue(diff.isInitial)
    assertEquals(listOf("title", "radius"), diff.changedNames)
    assertEquals(CardProps(title = "Card"), view().props)
    assertEquals(0.5f, view().alpha)
  }

  @Test
  fun `a mount brings the changes of commits whose props were freed before they mounted`() {
    val first = encoded.commit(null, "title" to "Card")
    create(JavaOnlyMap.of(key, first))

    val skipped = encoded.commit(first, "title" to "New card")
    val latest = encoded.commit(skipped, "radius" to 8.0)
    // The mount item of `skipped` finds its handle gone and does nothing; `latest` brings both changes.
    encoded.release(skipped)
    surface.updateProps(tag, JavaOnlyMap.of(key, skipped))
    surface.updateProps(tag, JavaOnlyMap.of("opacity", 0.5, key, latest))

    assertEquals(2, view().diffs.size)
    val diff = view().diffs.last()
    assertEquals(listOf("title", "radius"), diff.changedNames)
    assertEquals("Card", diff.oldProps?.title)
    assertEquals(CardProps(title = "New card", radius = 8.0), view().props)
    assertEquals(0.5f, view().alpha)
  }

  @Test
  fun `an update of base view props only leaves the view's props alone`() {
    create(JavaOnlyMap.of(key, encoded.commit(null, "title" to "Card")))

    surface.updateProps(tag, JavaOnlyMap.of("opacity", 0.25))

    assertEquals(1, view().diffs.size)
    assertEquals(0.25f, view().alpha)
  }

  @Test
  fun `a null value resets the prop to its default`() {
    val first = encoded.commit(null, "title" to "Card", "radius" to 10.0)
    create(JavaOnlyMap.of(key, first))

    surface.updateProps(tag, JavaOnlyMap.of(key, encoded.commit(first, "title" to null)))

    assertEquals(listOf("title"), view().diffs.last().changedNames)
    assertEquals(CardProps(radius = 10.0), view().props)
  }

  @Test
  fun `a view that React set none of the props of starts from the defaults`() {
    create(JavaOnlyMap.of())

    val diff = view().diffs.single()
    assertTrue(diff.isInitial)
    assertEquals(CardProps(), view().props)
  }

  private fun mockCatalystInstance(): CatalystInstance {
    val queues = MountingHarness.mainThreadQueues()
    return mock<CatalystInstance>().also {
      whenever(it.reactQueueConfiguration).thenReturn(queues)
      whenever(it.getNativeModule(UIManagerModule::class.java)).thenReturn(mock<UIManagerModule>())
      whenever(it.isDestroyed).thenReturn(false)
    }
  }
}
