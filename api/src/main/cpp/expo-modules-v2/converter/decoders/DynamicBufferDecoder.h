#pragma once

#include <jsi/jsi.h>

#include <expo-modules-v2/converter/TagVisit.h>
#include <kolibri/binary/BinaryReader.h>

namespace expo::modules::v2 {
  struct DynamicBufferDecoder {
    facebook::jsi::Runtime& rt;
    kolibri::binary::Reader& in;

    template<Tag T>
    facebook::jsi::Value operator()() const = delete;
  };

  // clang-format off
  template<> facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::BOOLEAN>() const;
  template<> facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::INT>() const;
  template<> facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::LONG>() const;
  template<> facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::FLOAT>() const;
  template<> facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::DOUBLE>() const;

  template<> facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::NULLTAG>() const;
  template<> facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::STRING>() const;
  template<> facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::BYTE_ARRAY>() const;

  template<> facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::LIST>() const;
  template<> facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::MAP>() const;
  template<> facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::EXTERNAL_SCHEMA>() const;

  template<> facebook::jsi::Value DynamicBufferDecoder::operator()<Tag::TAGGED>() const;
  // clang-format on
} // namespace expo::modules::v2
