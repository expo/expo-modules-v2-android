package io.github.expo.modules.v2.react.views

import com.facebook.react.ReactPackage
import com.facebook.react.ViewManagerOnDemandReactPackage
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager
import io.github.expo.kolibri.CalledFromNative
import io.github.expo.modules.v2.react.ExpoModulesV2React
import io.github.expo.modules.v2.views.ViewPropsDecoder

/**
 * PROTOTYPE. Registers view types with Fabric: one generic C++ component descriptor per view type,
 * told apart by its name and props decoder.
 */
@CalledFromNative(by = "expo-modules-v2-react/views/ExpoViewRegistry.cpp")
object ExpoViewRegistry {
  init {
    ExpoModulesV2React.ensureLoaded()
  }

  /**
   * Adds a component descriptor for [name] to React Native's shared provider registry. It joins the
   * component registries already built from it as well, so a view type can be registered before or
   * after the first surface starts. Registering a name again does nothing.
   */
  fun register(name: String, decoder: ViewPropsDecoder<*>) = nativeRegister(name, decoder)

  @JvmStatic
  private external fun nativeRegister(name: String, decoder: Any)
}

/**
 * PROTOTYPE. The React package of a set of view types. Bridgeless React Native asks a
 * [ViewManagerOnDemandReactPackage] for its view managers by name, so none is created before
 * JavaScript renders it.
 */
class ExpoViewsPackage(
  private val views: List<ExpoViewDefinition<*, *>>,
) : ReactPackage, ViewManagerOnDemandReactPackage {
  private val byName = views.associateBy { it.name }

  init {
    for (view in views) {
      ExpoViewRegistry.register(view.name, view.decoder)
    }
  }

  override fun getViewManagerNames(reactContext: ReactApplicationContext): Collection<String> = byName.keys

  override fun createViewManager(reactContext: ReactApplicationContext, viewManagerName: String): ViewManager<*, *>? =
    byName[viewManagerName]?.let { ExpoViewManager(it) }

  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
    views.map { ExpoViewManager(it) }
}
