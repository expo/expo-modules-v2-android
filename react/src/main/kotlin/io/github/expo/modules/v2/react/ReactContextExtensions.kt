package io.github.expo.modules.v2.react

import android.app.Activity
import com.facebook.react.bridge.ReactApplicationContext
import io.github.expo.modules.v2.ExpoObject

/**
 * The [ReactApplicationContext] of the app this module or shared object belongs to. Throws when
 * the object has no context yet, when that context is closed, or when it is not a
 * [ReactExpoContext].
 *
 * See [ExpoObject.context] for when an object gets its context.
 */
val ExpoObject.reactContext: ReactApplicationContext
  get() {
    val context = context
    return (context as? ReactExpoContext)?.reactContext ?: error(
      "${javaClass.name} belongs to a ${context.javaClass.name}, which has no " +
        "ReactApplicationContext",
    )
  }

/** Like [reactContext], but null instead of throwing. */
val ExpoObject.reactContextOrNull: ReactApplicationContext?
  get() = (contextOrNull as? ReactExpoContext)?.reactContext

/** The activity React Native currently runs in, or null when there is none. */
val ExpoObject.currentActivity: Activity?
  get() = reactContextOrNull?.currentActivity
