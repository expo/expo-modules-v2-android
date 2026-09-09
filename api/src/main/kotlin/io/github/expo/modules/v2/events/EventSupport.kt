package io.github.expo.modules.v2.events

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.modules.v2.ExpoObject
import io.github.expo.modules.v2.args.Bridge
import io.github.expo.modules.v2.args.Trampoline
import io.github.expo.modules.v2.async.AsyncContext
import io.github.expo.modules.v2.core.ExpoModulesV2
import io.github.expo.modules.v2.types.TypeDescriptor

/**
 * The bridge's side of [Event]: binding an event to its name and type, the observation hooks the
 * native side calls, and the delivery of an emit to one runtime.
 */
object EventSupport {
  /** Guards every [Event]'s observer set. */
  internal val lock = Any()

  /**
   * Gives [event] the name JavaScript subscribes to and the type its payload crosses as, and
   * records it on its owner so the native side can find it by name. Returns [event].
   *
   * The compiler plugin wraps every `@Event` property initializer in this call; a hand-described
   * class calls it itself, with the same type it declares through `ModuleBuilder.event`.
   */
  @JvmStatic
  fun <T> bind(
    event: Event<T>,
    jsName: String,
    descriptor: TypeDescriptor,
    useBuffer: Boolean = false,
  ): Event<T> {
    check(event.jsName == null) { "$event is already bound" }
    event.jsName = jsName
    event.descriptor = descriptor
    event.useBuffer = useBuffer

    val owner = event.owner
    val events = owner.events ?: HashMap<String, Event<*>>(2).also { owner.events = it }
    require(events.put(jsName, event) == null) {
      "${owner.javaClass.name} declares two events named '$jsName'"
    }
    return event
  }

  /**
   * Called when [context]'s runtime gains its first listener for the event, or loses its last one.
   * Runs on that runtime's JS thread.
   */
  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JEventSupport.h")
  fun observe(instance: ExpoObject, jsName: String, context: AsyncContext, observing: Boolean) {
    val event = instance.events?.get(jsName) ?: return
    if (observing) {
      event.attach(context)
    } else {
      event.detach(context)
    }
  }

  /**
   * Runs on [context]'s JS thread. Converts [payload] the way an export's result crosses and hands
   * it to the runtime, which calls the listeners of [owner]'s JavaScript object.
   *
   * A failure here is printed, never thrown: the emitter's Kotlin code must not see a listener's
   * or a conversion's error, the same rule `DefaultJSScheduler` applies to any JS-thread job.
   */
  internal fun deliver(
    context: AsyncContext,
    owner: ExpoObject,
    name: String,
    descriptor: TypeDescriptor,
    useBuffer: Boolean,
    payload: Any?,
  ) {
    val pointer = context.runtimePointer
    if (pointer == 0L) {
      return
    }

    try {
      if (useBuffer) {
        EventNatives.emitBuffered(
          pointer,
          owner.objectId,
          name,
          Trampoline.writeResult(payload, descriptor),
        )
      } else {
        EventNatives.emit(
          pointer,
          owner.objectId,
          name,
          Bridge.toJni(payload, descriptor)
        )
      }
    } catch (throwable: Throwable) {
      System.err.println("expo-modules-v2: emitting '$name' on ${owner.javaClass.name} failed")
      throwable.printStackTrace()
    }
  }
}

/**
 * The natives an emit goes through. Kept apart from [EventSupport] so that binding an event - which
 * happens while the owning object is constructed - never loads the native library; only the first
 * delivery does, and by then a runtime exists.
 */
internal object EventNatives {
  init {
    ExpoModulesV2.load()
  }

  fun emit(runtimePointer: Long, objectId: Long, name: String, value: Any?) =
    nativeEmit(runtimePointer, objectId, name, value)

  fun emitBuffered(runtimePointer: Long, objectId: Long, name: String, payloadLength: Int) =
    nativeEmitBuffered(runtimePointer, objectId, name, payloadLength)

  @JvmStatic
  private external fun nativeEmit(runtimePointer: Long, objectId: Long, name: String, value: Any?)

  @JvmStatic
  private external fun nativeEmitBuffered(
    runtimePointer: Long,
    objectId: Long,
    name: String,
    payloadLength: Int,
  )
}
