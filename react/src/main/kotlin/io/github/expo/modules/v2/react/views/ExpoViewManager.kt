package io.github.expo.modules.v2.react.views

import com.facebook.react.uimanager.ReactStylesDiffMap
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import io.github.expo.modules.v2.views.EncodedViewPropsReader
import io.github.expo.modules.v2.views.ViewPropsDecoder
import io.github.expo.modules.v2.views.ViewPropsNatives

/**
 * PROTOTYPE. One view type: its component name, its props decoder, and how to make its view.
 * What `@ExpoView` plus `@ExpoModule(views = [...])` would generate.
 */
class ExpoViewDefinition<V : ExpoNativeView<P>, P : Any>(
  /** The component name in React Native, which is also the view manager's name. */
  val name: String,
  val decoder: ViewPropsDecoder<P>,
  val createView: (ThemedReactContext) -> V,
  /** Where encoded props are read from: native in an app, a Kotlin writer in JVM tests. */
  internal val reader: EncodedViewPropsReader = ViewPropsNatives,
)

/**
 * PROTOTYPE. The view manager of one view type of Expo Modules v2.
 *
 * The component descriptor keeps the view's own props out of the raw props Java gets and puts the
 * handle of their binary form there instead, under [ENCODED_PROPS_KEY]. So each props update brings
 * both halves in one [updateProperties] call: the base view props (style, layout, accessibility),
 * which [SimpleViewManager] applies as it does for any view, and the handle, which the view mounts.
 * There's no state involved.
 */
class ExpoViewManager<V : ExpoNativeView<P>, P : Any>(
  private val definition: ExpoViewDefinition<V, P>,
) : SimpleViewManager<V>() {
  override fun getName(): String = definition.name

  override fun createViewInstance(reactContext: ThemedReactContext): V = definition.createView(reactContext)

  override fun updateProperties(viewToUpdate: V, props: ReactStylesDiffMap) {
    // The base view props first, so `didUpdateProps` sees the view with its new style and layout.
    // The handle is not a prop any setter knows, so the base view manager skips it.
    super.updateProperties(viewToUpdate, props)

    // A double, as every number a `ReadableNativeMap` holds: handles count up from 1, so they stay
    // exact far past any number of props updates an app makes (2^53).
    if (props.hasKey(ENCODED_PROPS_KEY)) {
      viewToUpdate.mount(props.getDouble(ENCODED_PROPS_KEY, 0.0).toLong(), definition.decoder, definition.reader)
    }
    viewToUpdate.mountInitialIfNeeded(definition.decoder)
  }

  companion object {
    /** Must stay in sync with `kEncodedPropsKey` in `ExpoViewComponentDescriptor.h`. */
    const val ENCODED_PROPS_KEY = "__expoViewProps"
  }
}
