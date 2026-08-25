#pragma once

#include <expo-modules-v2/descriptor/CppType.h>
#include <expo-modules-v2/utils/invoke.h>

namespace expo::modules::v2 {
  /**
   * Turns a runtime *leaf* kind into `v`'s template argument - the codecs' only runtime test of a
   * kind. The handler is `v`'s explicit specialization for that kind, so it never has to re-examine
   * the kind it was given:
   *
   *   return visitLeafKind<Value>(kind, BufferDecoder{rt, in});
   *
   * A visitor covers the kinds it can represent and nothing else: every kind it declines throws, so
   * adding a [CppType] does not break visitors that cannot represent it. The result type is spelled
   * by the caller for the same reason - a declining arm has no handler to deduce it from.
   *
   * LIST/MAP/RECORD are not leaves and never reach `v` - see [visitCppType] for the codes that
   * arrive without a declared type.
   */
  template<typename R, typename V>
  R visitLeafKind(const CppType kind, V&& v) {
    switch (kind) {
      case CppType::BOOLEAN: return invokeFor<R, CppType::BOOLEAN>(v);
      case CppType::STRING: return invokeFor<R, CppType::STRING>(v);
      case CppType::INT: return invokeFor<R, CppType::INT>(v);
      case CppType::LONG: return invokeFor<R, CppType::LONG>(v);
      case CppType::FLOAT: return invokeFor<R, CppType::FLOAT>(v);
      case CppType::DOUBLE: return invokeFor<R, CppType::DOUBLE>(v);
      case CppType::ANY: return invokeFor<R, CppType::ANY>(v);
      case CppType::DOUBLE_ARRAY: return invokeFor<R, CppType::DOUBLE_ARRAY>(v);
      case CppType::INT_ARRAY: return invokeFor<R, CppType::INT_ARRAY>(v);
      case CppType::LONG_ARRAY: return invokeFor<R, CppType::LONG_ARRAY>(v);
      case CppType::FLOAT_ARRAY: return invokeFor<R, CppType::FLOAT_ARRAY>(v);
      case CppType::BOOLEAN_ARRAY: return invokeFor<R, CppType::BOOLEAN_ARRAY>(v);
      case CppType::BYTE_ARRAY: return invokeFor<R, CppType::BYTE_ARRAY>(v);
      case CppType::JS_VALUE: return invokeFor<R, CppType::JS_VALUE>(v);
      case CppType::JS_OBJECT: return invokeFor<R, CppType::JS_OBJECT>(v);
      case CppType::UNIT: return invokeFor<R, CppType::UNIT>(v);
      case CppType::BOX_INT: return invokeFor<R, CppType::BOX_INT>(v);
      case CppType::BOX_BOOLEAN: return invokeFor<R, CppType::BOX_BOOLEAN>(v);
      case CppType::BOX_LONG: return invokeFor<R, CppType::BOX_LONG>(v);
      case CppType::BOX_FLOAT: return invokeFor<R, CppType::BOX_FLOAT>(v);
      case CppType::BOX_DOUBLE: return invokeFor<R, CppType::BOX_DOUBLE>(v);
      default:
        throwUnknown(kind);
    }
  }

  /**
   * [visitLeafKind] plus the container codes, for values that arrive without a declared type (the
   * dynamic codecs, where `DynamicTypes.kindOf` classifies the JVM object). A typed codec visits
   * its [ExpectedType] instead and gets the container's element/value/schema with it.
   */
  template<typename R, typename V>
  R visitCppType(const CppType kind, V&& v) {
    switch (kind) {
      case CppType::LIST: return invokeFor<R, CppType::LIST>(v);
      case CppType::MAP: return invokeFor<R, CppType::MAP>(v);
      case CppType::RECORD: return invokeFor<R, CppType::RECORD>(v);
      default: return visitLeafKind<R>(kind, v);
    }
  }
} // namespace expo::modules::v2
