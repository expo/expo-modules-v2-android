#pragma once

#include <expo-modules-v2/utils/invoke.h>
#include <kolibri/binary/BinaryFormat.h>

namespace expo::modules::v2 {
  using kolibri::binary::Tag;

  template<typename R, typename V>
  R visitTag(const Tag tag, V&& v) {
    switch (tag) {
      case Tag::BOOLEAN: return invokeFor<R, Tag::BOOLEAN>(v);
      case Tag::STRING: return invokeFor<R, Tag::STRING>(v);
      case Tag::INT: return invokeFor<R, Tag::INT>(v);
      case Tag::LONG: return invokeFor<R, Tag::LONG>(v);
      case Tag::FLOAT: return invokeFor<R, Tag::FLOAT>(v);
      case Tag::DOUBLE: return invokeFor<R, Tag::DOUBLE>(v);
      case Tag::LIST: return invokeFor<R, Tag::LIST>(v);
      case Tag::MAP: return invokeFor<R, Tag::MAP>(v);
      case Tag::NULLTAG: return invokeFor<R, Tag::NULLTAG>(v);
      case Tag::BYTE_ARRAY: return invokeFor<R, Tag::BYTE_ARRAY>(v);
      case Tag::EXTERNAL_SCHEMA: return invokeFor<R, Tag::EXTERNAL_SCHEMA>(v);
      case Tag::TAGGED: return invokeFor<R, Tag::TAGGED>(v);
      default:
        throwUnknown(tag);
    }
  }
} // namespace expo::modules::v2
