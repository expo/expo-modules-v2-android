package io.github.expo.modules.v2.sharedobjects

abstract class SharedRef<T : Any>(val ref: T) : SharedObject() {
  open val nativeRefType: String
    get() = ref.javaClass.simpleName
}
