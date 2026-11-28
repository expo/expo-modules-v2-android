package io.github.expo.modules.v2.views

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.binary.newSharedView
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.TypeCodesWriter
import java.nio.BufferOverflowException

/** One prop of a view: the name JavaScript sets it by, and the type its value is encoded as. */
class ViewPropSpec(val name: String, val type: AnyType)

/**
 * PROTOTYPE. Describes the props of one view type, and turns their binary form into an immutable [P].
 *
 * What a `@ViewProps` class would generate; written by hand while the prototype settles the transport.
 * The native codec (`expo-modules-v2/views/ViewPropsCodec.h`) encodes each prop on the thread that
 * commits it, with the type from [props]. The view reads the props that changed when the UI thread
 * mounts them ([mount]), and this class decodes them with the same types' converters.
 *
 * One decoder serves every view of its type, so implementations must be stateless.
 */
@CalledFromNative(by = "expo-modules-v2/views/ViewPropsCodec.h")
abstract class ViewPropsDecoder<P : Any>(val props: List<ViewPropSpec>) {
  init {
    require(props.size <= MAX_PROPS) {
      "A view has at most $MAX_PROPS props; this one declares ${props.size}"
    }
    require(props.map { it.name }.toSet().size == props.size) { "Prop names must be unique" }
  }

  /** The props a view starts from. React sends only the props that are set. */
  abstract fun defaults(): P

  /**
   * Returns [props] with prop `indices[i]` set to `values[i]`. A null value resets the prop to its
   * value in [defaults]: React sends a removed prop as `null`.
   */
  protected abstract fun apply(props: P, indices: IntArray, values: Array<Any?>): P

  /** [apply], for the benchmark baseline in `views.baseline`. */
  internal fun applyUpdate(props: P, indices: IntArray, values: Array<Any?>): P = apply(props, indices, values)

  /** The index of the prop named [name], or -1. */
  fun indexOf(name: String): Int = props.indexOfFirst { it.name == name }

  /** The props of a view that React set none of. */
  fun initial(): MountedViewProps<P> = MountedViewProps(this, defaults(), LongArray(props.size))

  /**
   * Mounts the props [handle] stands for on top of [mounted], the props the view has (null for a new
   * view). Returns null when there is nothing to do: the handle no longer resolves, because a later
   * commit replaced those props and its mount item carries the newer handle, or no prop changed.
   */
  fun mount(handle: Long, mounted: MountedViewProps<P>?, reader: EncodedViewPropsReader): PropsDiff<P>? {
    val buf = reader.readChanges(handle, mounted?.revisions) ?: return null
    return mountChanges(buf, mounted)
  }

  /** Applies the entries `EncodedViewProps::writeChanges` wrote into [buf] on top of [mounted]. */
  fun mountChanges(buf: BinaryBuffer, mounted: MountedViewProps<P>?): PropsDiff<P>? {
    val count = buf.getInt()
    if (count == 0 && mounted != null) {
      return null
    }
    val indices = IntArray(count)
    val values = arrayOfNulls<Any?>(count)
    val revisions = mounted?.revisions?.copyOf() ?: LongArray(props.size)
    var changed = 0L
    for (i in 0 until count) {
      val index = buf.getInt()
      revisions[index] = buf.getLong()
      indices[i] = index
      values[i] = when (buf.getTag()) {
        ENTRY_VALUE -> props[index].type.anyConverter.readFromBuffer(buf)
        else -> null
      }
      changed = changed or (1L shl index)
    }
    val base = mounted?.props ?: defaults()
    val new = MountedViewProps(this, apply(base, indices, values), revisions)
    // The first props of a view are all new to it, set by React or not.
    return PropsDiff(mounted, new, if (mounted == null) allPropsMask() else changed)
  }

  internal fun allPropsMask(): Long =
    if (props.size == MAX_PROPS) -1L else (1L shl props.size) - 1

  /**
   * Writes the prop names and types into the shared bridge buffer, in the order native code indexes
   * them: `count: i32, count x { name: string, typeCodes: intArray }`. Returns the payload's length.
   */
  @Suppress("unused")
  @CalledFromNative(by = "expo-modules-v2/views/ViewPropsCodec.h")
  private fun encodeSpec(): Int {
    val buf = BinaryBuffer.newSharedView()
    val codes = TypeCodesWriter()
    try {
      buf.putInt(props.size)
      for (prop in props) {
        buf.putString(prop.name)
        codes.reset()
        prop.type.writeTypeCodes(codes)
        codes.writeTo(buf)
      }
    } catch (overflow: BufferOverflowException) {
      throw IllegalArgumentException("The view's props do not fit in the bridge buffer", overflow)
    }
    return buf.position
  }

  companion object {
    /** One bit for each prop in the 64-bit sets [PropsDiff.changedProps] holds. */
    const val MAX_PROPS = 64

    /** Entry tags of the payload, as `EncodedViewProps::kEntryValue`/`kEntryReset` write them. */
    const val ENTRY_VALUE = 1
    const val ENTRY_RESET = 0
  }
}

/**
 * PROTOTYPE. The props a view has mounted, and the revision in which each last changed (0 for a prop
 * React never set). The revisions are what the view hands native code at the next mount, which then
 * writes only the props whose revision differs - so a commit that never mounted can't lose a change.
 */
class MountedViewProps<P : Any> internal constructor(
  val decoder: ViewPropsDecoder<P>,
  val props: P,
  internal val revisions: LongArray,
)

/** PROTOTYPE. What a view gets on each update: the props before and after, and what changed. */
class PropsDiff<P : Any>(
  val old: MountedViewProps<P>?,
  val new: MountedViewProps<P>,
  /** The changed props, one bit for each prop index. All props on a view's first update. */
  val changedProps: Long,
) {
  val isInitial: Boolean get() = old == null

  val newProps: P get() = new.props

  val oldProps: P? get() = old?.props

  fun changed(index: Int): Boolean = changedProps and (1L shl index) != 0L

  fun changed(name: String): Boolean {
    val index = new.decoder.indexOf(name)
    require(index >= 0) { "No prop named '$name'" }
    return changed(index)
  }

  /** The names of the changed props, in declaration order. */
  val changedNames: List<String>
    get() = new.decoder.props.filterIndexed { index, _ -> changed(index) }.map { it.name }
}
