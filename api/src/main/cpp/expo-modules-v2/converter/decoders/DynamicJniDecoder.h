#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <expo-modules-v2/descriptor/CppType.h>

namespace expo::modules::v2 {
  struct DynamicJniDecoder {
    JNIEnv* env;
    facebook::jsi::Runtime& rt;
    jobject object;

    template<CppType K>
    facebook::jsi::Value operator()() const = delete;
  };

  // clang-format off
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::INT>() const;
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BOX_INT>() const;

  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::LONG>() const;
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BOX_LONG>() const;

  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::FLOAT>() const;
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BOX_FLOAT>() const;

  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::DOUBLE>() const;
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BOX_DOUBLE>() const;

  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BOOLEAN>() const;
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BOX_BOOLEAN>() const;

  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BOOLEAN_ARRAY>() const;
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::INT_ARRAY>() const;
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::LONG_ARRAY>() const;
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::FLOAT_ARRAY>() const;
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::DOUBLE_ARRAY>() const;
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::BYTE_ARRAY>() const;

  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::UNIT>() const;
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::STRING>() const;
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::JS_VALUE>() const;
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::JS_OBJECT>() const;

  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::LIST>() const;
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::MAP>() const;
  template<> facebook::jsi::Value DynamicJniDecoder::operator()<CppType::RECORD>() const;
  // clang-format on
} // namespace expo::modules::v2
