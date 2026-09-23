package io.github.expo.modules.v2.react

import com.facebook.react.bridge.ReactApplicationContext
import io.github.expo.modules.v2.ExpoContext

/**
 * The [ExpoContext] of a React Native app. Every runtime attached for [reactContext] can share one,
 * so a module or shared object that serves several of them reaches one [ReactApplicationContext].
 */
class ReactExpoContext(val reactContext: ReactApplicationContext) : ExpoContext()
