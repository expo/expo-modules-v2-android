package io.github.expo.modules.v2

abstract class SharedRef<T : Any> : SharedObject {
  val ref: T

  constructor(ref: T) : super() {
    this.ref = ref
  }

  constructor(context: ExpoContext, ref: T) : super(context) {
    this.ref = ref
  }

  open val nativeRefType: String
    get() = ref.javaClass.simpleName
}
