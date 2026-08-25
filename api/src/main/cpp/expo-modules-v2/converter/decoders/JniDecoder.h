#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <expo-modules-v2/descriptor/CppType.h>
#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2 {
  struct JniDecoder {
    JNIEnv* env;
    facebook::jsi::Runtime& rt;
    jobject object;

    template<CppType K>
    facebook::jsi::Value operator()() const = delete;

    facebook::jsi::Value operator()(const ExpectedType::List& listType) const;

    facebook::jsi::Value operator()(const ExpectedType::Map& mapType) const;

    facebook::jsi::Value operator()(const ExpectedType::Record& recordType) const;
  };

  // clang-format off
  template<> facebook::jsi::Value JniDecoder::operator()<CppType::INT>() const;
  template<> facebook::jsi::Value JniDecoder::operator()<CppType::BOX_INT>() const;

  template<> facebook::jsi::Value JniDecoder::operator()<CppType::LONG>() const;
  template<> facebook::jsi::Value JniDecoder::operator()<CppType::BOX_LONG>() const;

  template<> facebook::jsi::Value JniDecoder::operator()<CppType::FLOAT>() const;
  template<> facebook::jsi::Value JniDecoder::operator()<CppType::BOX_FLOAT>() const;

  template<> facebook::jsi::Value JniDecoder::operator()<CppType::DOUBLE>() const;
  template<> facebook::jsi::Value JniDecoder::operator()<CppType::BOX_DOUBLE>() const;

  template<> facebook::jsi::Value JniDecoder::operator()<CppType::BOOLEAN>() const;
  template<> facebook::jsi::Value JniDecoder::operator()<CppType::BOX_BOOLEAN>() const;

  template<> facebook::jsi::Value JniDecoder::operator()<CppType::BOOLEAN_ARRAY>() const;
  template<> facebook::jsi::Value JniDecoder::operator()<CppType::INT_ARRAY>() const;
  template<> facebook::jsi::Value JniDecoder::operator()<CppType::LONG_ARRAY>() const;
  template<> facebook::jsi::Value JniDecoder::operator()<CppType::FLOAT_ARRAY>() const;
  template<> facebook::jsi::Value JniDecoder::operator()<CppType::DOUBLE_ARRAY>() const;
  template<> facebook::jsi::Value JniDecoder::operator()<CppType::BYTE_ARRAY>() const;

  template<> facebook::jsi::Value JniDecoder::operator()<CppType::UNIT>() const;
  template<> facebook::jsi::Value JniDecoder::operator()<CppType::STRING>() const;
  template<> facebook::jsi::Value JniDecoder::operator()<CppType::JS_VALUE>() const;
  template<> facebook::jsi::Value JniDecoder::operator()<CppType::JS_OBJECT>() const;
  template<> facebook::jsi::Value JniDecoder::operator()<CppType::ANY>() const;
  // clang-format on
} // namespace expo::modules::v2
