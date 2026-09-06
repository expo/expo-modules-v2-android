#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <expo-modules-v2/descriptor/CppType.h>
#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2 {
  struct JniEncoder {
    JNIEnv* env;
    facebook::jsi::Runtime& rt;
    const facebook::jsi::Value& value;

    template<CppType K>
    jobject operator()() const = delete;

    jobject operator()(const ExpectedType::List& listType) const;

    jobject operator()(const ExpectedType::Map& mapType) const;

    jobject operator()(const ExpectedType::Record& recordType) const;

    jobject operator()(const ExpectedType::SharedObject& sharedType) const;
  };

  // clang-format off
  template<> jobject JniEncoder::operator()<CppType::INT>() const;
  template<> jobject JniEncoder::operator()<CppType::BOX_INT>() const;

  template<> jobject JniEncoder::operator()<CppType::LONG>() const;
  template<> jobject JniEncoder::operator()<CppType::BOX_LONG>() const;

  template<> jobject JniEncoder::operator()<CppType::FLOAT>() const;
  template<> jobject JniEncoder::operator()<CppType::BOX_FLOAT>() const;

  template<> jobject JniEncoder::operator()<CppType::DOUBLE>() const;
  template<> jobject JniEncoder::operator()<CppType::BOX_DOUBLE>() const;

  template<> jobject JniEncoder::operator()<CppType::BOOLEAN>() const;
  template<> jobject JniEncoder::operator()<CppType::BOX_BOOLEAN>() const;

  template<> jobject JniEncoder::operator()<CppType::BOOLEAN_ARRAY>() const;
  template<> jobject JniEncoder::operator()<CppType::INT_ARRAY>() const;
  template<> jobject JniEncoder::operator()<CppType::LONG_ARRAY>() const;
  template<> jobject JniEncoder::operator()<CppType::FLOAT_ARRAY>() const;
  template<> jobject JniEncoder::operator()<CppType::DOUBLE_ARRAY>() const;
  template<> jobject JniEncoder::operator()<CppType::BYTE_ARRAY>() const;

  template<> jobject JniEncoder::operator()<CppType::UNIT>() const;
  template<> jobject JniEncoder::operator()<CppType::STRING>() const;
  template<> jobject JniEncoder::operator()<CppType::JS_OBJECT>() const;
  template<> jobject JniEncoder::operator()<CppType::ANY>() const;
  // clang-format on
} // namespace expo::modules::v2
