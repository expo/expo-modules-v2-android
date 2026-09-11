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
   * records it on its owner at the next index, which is how the native side refers to it. Returns
   * [event].
   *
   * The compiler plugin wraps every `@Event` property initializer in this call; a hand-described
   * class calls it itself, with the same type it declares through `ModuleBuilder.event`, and in
   * the same order - see [checkDeclaration].
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
    val events = owner.events ?: ArrayList<Event<*>>(2).also { owner.events = it }
    event.index = events.size
    events.add(event)
    return event
  }

  /**
   * Called when [context]'s runtime gains its first listener for the event at [index], or loses
   * its last one. Runs on that runtime's JS thread.
   */
  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JEventSupport.h")
  fun observe(instance: ExpoObject, index: Int, context: AsyncContext, observing: Boolean) {
    val event = instance.events?.getOrNull(index) ?: return
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
    event: Event<*>,
    name: String,
    descriptor: TypeDescriptor,
    useBuffer: Boolean,
    payload: Any?,
  ) {
    val pointer = context.runtimePointer
    if (pointer == 0L) {
      return
    }
    val owner = event.owner

    try {
      if (useBuffer) {
        EventNatives.emitBuffered(
          pointer,
          owner.objectId,
          event.index,
          Trampoline.writeResult(payload, descriptor),
        )
      } else {
        EventNatives.emit(
          pointer,
          owner.objectId,
          event.index,
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

  fun emit(runtimePointer: Long, objectId: Long, index: Int, value: Any?) =
    nativeEmit(runtimePointer, objectId, index, value)

  fun emitBuffered(runtimePointer: Long, objectId: Long, index: Int, payloadLength: Int) =
    nativeEmitBuffered(runtimePointer, objectId, index, payloadLength)

  @JvmStatic
  private external fun nativeEmit(runtimePointer: Long, objectId: Long, index: Int, value: Any?)

  @JvmStatic
  private external fun nativeEmitBuffered(runtimePointer: Long, objectId: Long, index: Int, payloadLength: Int)
}
