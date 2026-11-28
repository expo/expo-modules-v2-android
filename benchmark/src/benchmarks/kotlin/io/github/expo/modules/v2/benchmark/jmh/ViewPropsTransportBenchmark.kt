package io.github.expo.modules.v2.benchmark.jmh

import io.github.expo.modules.v2.jsi.JavaScriptValue
import io.github.expo.modules.v2.testsupport.HermesRuntime
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.CommonDescriptors
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.views.MountedViewProps
import io.github.expo.modules.v2.views.PropsDiff
import io.github.expo.modules.v2.views.ViewPropSpec
import io.github.expo.modules.v2.views.ViewPropsDecoder
import io.github.expo.modules.v2.views.ViewPropsNatives
import io.github.expo.modules.v2.views.baseline.StateViewPropsBaseline
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown

internal data class CardProps(
  val title: String = "",
  val subtitle: String = "",
  val radius: Double = 0.0,
  val elevation: Double = 0.0,
  val tags: List<String> = emptyList(),
  val points: List<Double> = emptyList(),
)

private val STRINGS = TypeDescriptor.Parametrized(List::class.java, false, arrayOf(CommonDescriptors.STRING))
private val DOUBLES = TypeDescriptor.Parametrized(List::class.java, false, arrayOf(CommonDescriptors.DOUBLE_BOXED))

/** What a `@ViewProps` class would generate. Both transports use this one. */
private object CardPropsDecoder : ViewPropsDecoder<CardProps>(
  listOf(
    ViewPropSpec("title", AnyType(CommonDescriptors.STRING)),
    ViewPropSpec("subtitle", AnyType(CommonDescriptors.STRING)),
    ViewPropSpec("radius", AnyType(CommonDescriptors.DOUBLE_BOXED)),
    ViewPropSpec("elevation", AnyType(CommonDescriptors.DOUBLE_BOXED)),
    ViewPropSpec("tags", AnyType(STRINGS)),
    ViewPropSpec("points", AnyType(DOUBLES)),
  ),
) {
  private val defaults = CardProps()

  override fun defaults() = defaults

  @Suppress("UNCHECKED_CAST")
  override fun apply(props: CardProps, indices: IntArray, values: Array<Any?>): CardProps {
    var title = props.title
    var subtitle = props.subtitle
    var radius = props.radius
    var elevation = props.elevation
    var tags = props.tags
    var points = props.points
    for (i in indices.indices) {
      val value = values[i]
      when (indices[i]) {
        0 -> title = value as String? ?: defaults.title
        1 -> subtitle = value as String? ?: defaults.subtitle
        2 -> radius = value as Double? ?: defaults.radius
        3 -> elevation = value as Double? ?: defaults.elevation
        4 -> tags = value as List<String>? ?: defaults.tags
        else -> points = value as List<Double>? ?: defaults.points
      }
    }
    return CardProps(title, subtitle, radius, elevation, tags, points)
  }
}

/** The props of one update, as React sends them: only what changed, plus base view props. */
private val CASES = mapOf(
  "1-string" to "{ title: 'Card title' }",
  "4-scalars" to "{ title: 'Card title', subtitle: 'A subtitle', radius: 8, elevation: 2, opacity: 0.5 }",
  "lists" to "{ title: 'Card title', " +
    "tags: Array.from({length: 10}, (_, i) => 'tag' + i), " +
    "points: Array.from({length: 50}, (_, i) => i * 0.5) }",
  "all-6" to "{ title: 'Card title', subtitle: 'A subtitle', radius: 8, elevation: 2, " +
    "tags: Array.from({length: 10}, (_, i) => 'tag' + i), " +
    "points: Array.from({length: 50}, (_, i) => i * 0.5), opacity: 0.5 }",
)

/**
 * PROTOTYPE. The two props transports of view prototypes, on the same props, decoder and runtime:
 *
 * - `state*`: the first prototype. Commit converts each prop to a Java object over JNI and calls
 *   Kotlin once to build the props; the shadow node's state carries them; mount reads them back.
 * - `binary*`: the current one. Commit encodes each prop into bytes with no JNI; the raw props carry a
 *   handle; mount makes one JNI call that writes the changed props, and Kotlin decodes them.
 *
 * `commit` is what runs on the JS thread in `cloneProps`, freeing the props it made, as Fabric does
 * once a later commit replaces them. `mount` is what runs on the UI thread, for an update that changed
 * the case's props. `roundTrip` is a stream of updates: commit, mount, free the previous props.
 *
 * Both sides include only this project's code. React Native's share - the extra `State`, `UpdateState`
 * mount item and `StateWrapperImpl` per update for `state`, one more number in the raw props for
 * `binary` - only exists on a device.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
class ViewPropsTransportBenchmark {
  @Param("1-string", "4-scalars", "lists", "all-6")
  var case: String = ""

  private lateinit var runtime: HermesRuntime
  private lateinit var props: JavaScriptValue

  private val baseline by lazy { StateViewPropsBaseline(CardPropsDecoder) }
  private var codec = 0L

  // The props an update builds on, and the next ones, already committed - for `commit` and `mount`.
  private var stateBase = 0L
  private var stateNext = 0L
  private lateinit var stateMounted: StateViewPropsBaseline.Decoded<CardProps>
  private var binaryBase = 0L
  private var binaryNext = 0L
  private lateinit var binaryMounted: MountedViewProps<CardProps>

  // What `roundTrip` advances.
  private var stateCurrent = 0L
  private var stateViewProps: StateViewPropsBaseline.Decoded<CardProps>? = null
  private var binaryCurrent = 0L
  private var binaryViewProps: MountedViewProps<CardProps>? = null

  @Setup
  fun setUp() {
    NativeLibraries.load()
    runtime = HermesRuntime()
    props = runtime.evaluate("(${CASES.getValue(case)})")
    codec = ViewPropsNatives.createCodec(CardPropsDecoder)

    stateBase = baseline.commit(props, 0)
    stateNext = baseline.commit(props, stateBase)
    stateMounted = baseline.reference(stateBase)
    binaryBase = ViewPropsNatives.encodeForTesting(codec, props, 0)
    binaryNext = ViewPropsNatives.encodeForTesting(codec, props, binaryBase)
    binaryMounted = CardPropsDecoder.mount(ViewPropsNatives.handleOf(binaryBase), null, ViewPropsNatives)!!.new

    stateCurrent = baseline.commit(props, 0)
    binaryCurrent = ViewPropsNatives.encodeForTesting(codec, props, 0)

    // Both must mount the same props, or the comparison means nothing.
    val viaState = stateMount().newProps
    val viaBinary = binaryMount().newProps
    check(viaState == viaBinary) { "The transports disagree: $viaState vs $viaBinary" }
  }

  @TearDown
  fun tearDown() {
    listOf(stateBase, stateNext, stateCurrent).forEach(baseline::release)
    listOf(binaryBase, binaryNext, binaryCurrent).forEach(ViewPropsNatives::releaseOwner)
    baseline.close()
    ViewPropsNatives.destroyCodec(codec)
    runtime.close()
  }

  @Benchmark
  fun stateCommit(): Long = baseline.commit(props, stateBase).also(baseline::release)

  @Benchmark
  fun binaryCommit(): Long = ViewPropsNatives.encodeForTesting(codec, props, binaryBase).also(ViewPropsNatives::releaseOwner)

  @Benchmark
  fun stateMount(): PropsDiff<*> = baseline.mount(baseline.reference(stateNext), stateMounted)

  @Benchmark
  fun binaryMount(): PropsDiff<*> =
    CardPropsDecoder.mount(ViewPropsNatives.handleOf(binaryNext), binaryMounted, ViewPropsNatives)!!

  @Benchmark
  fun stateRoundTrip(): PropsDiff<*> {
    val next = baseline.commit(props, stateCurrent)
    val decoded = baseline.reference(next)
    val diff = baseline.mount(decoded, stateViewProps)
    stateViewProps = decoded
    baseline.release(stateCurrent)
    stateCurrent = next
    return diff
  }

  @Benchmark
  fun binaryRoundTrip(): PropsDiff<*> {
    val next = ViewPropsNatives.encodeForTesting(codec, props, binaryCurrent)
    val diff = CardPropsDecoder.mount(ViewPropsNatives.handleOf(next), binaryViewProps, ViewPropsNatives)!!
    binaryViewProps = diff.new
    ViewPropsNatives.releaseOwner(binaryCurrent)
    binaryCurrent = next
    return diff
  }
}
