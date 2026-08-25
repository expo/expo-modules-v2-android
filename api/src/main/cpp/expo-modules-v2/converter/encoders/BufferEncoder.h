#pragma once

#include <jsi/jsi.h>

#include <expo-modules-v2/descriptor/CppType.h>
#include <expo-modules-v2/descriptor/ExpectedType.h>
#include <kolibri/binary/BinaryBuffer.h>

namespace expo::modules::v2 {
  struct BufferEncoder {
    facebook::jsi::Runtime& rt;
    const facebook::jsi::Value& value;
    kolibri::binary::BinaryBuffer& out;

    template<CppType K>
    void operator()() const = delete;

    void operator()(const ExpectedType::List& listType) const;

    void operator()(const ExpectedType::Map& mapType) const;

    void operator()(const ExpectedType::Record& recordType) const;
  };

  // clang-format off
  template<> void BufferEncoder::operator()<CppType::INT>() const;
  template<> void BufferEncoder::operator()<CppType::BOX_INT>() const;

  template<> void BufferEncoder::operator()<CppType::LONG>() const;
  template<> void BufferEncoder::operator()<CppType::BOX_LONG>() const;

  template<> void BufferEncoder::operator()<CppType::FLOAT>() const;
  template<> void BufferEncoder::operator()<CppType::BOX_FLOAT>() const;

  template<> void BufferEncoder::operator()<CppType::DOUBLE>() const;
  template<> void BufferEncoder::operator()<CppType::BOX_DOUBLE>() const;

  template<> void BufferEncoder::operator()<CppType::BOOLEAN>() const;
  template<> void BufferEncoder::operator()<CppType::BOX_BOOLEAN>() const;

  template<> void BufferEncoder::operator()<CppType::BOOLEAN_ARRAY>() const;
  template<> void BufferEncoder::operator()<CppType::INT_ARRAY>() const;
  template<> void BufferEncoder::operator()<CppType::LONG_ARRAY>() const;
  template<> void BufferEncoder::operator()<CppType::FLOAT_ARRAY>() const;
  template<> void BufferEncoder::operator()<CppType::DOUBLE_ARRAY>() const;
  template<> void BufferEncoder::operator()<CppType::BYTE_ARRAY>() const;

  template<> void BufferEncoder::operator()<CppType::UNIT>() const;
  template<> void BufferEncoder::operator()<CppType::STRING>() const;
  template<> void BufferEncoder::operator()<CppType::ANY>() const;
  // clang-format on
} // namespace expo::modules::v2
