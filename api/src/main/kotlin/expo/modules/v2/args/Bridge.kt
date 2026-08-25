package expo.modules.v2.args

import expo.modules.v2.types.TypeDescriptor
import expo.modules.v2.types.anyConverter

object Bridge {
  @JvmStatic
  fun fromJni(value: Any?, type: TypeDescriptor): Any? = type.anyConverter.fromJni(value)

  @JvmStatic
  fun toJni(value: Any?, type: TypeDescriptor): Any? = type.anyConverter.toJni(value)
}
