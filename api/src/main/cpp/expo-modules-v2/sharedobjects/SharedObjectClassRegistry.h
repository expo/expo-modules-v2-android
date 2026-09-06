#pragma once

#include <jni.h>

#include <string>

#include <expo-modules-v2/sharedobjects/SharedObjectClassSpec.h>

namespace expo::modules::v2::sharedobjects {
  class SharedObjectClassRegistry {
  public:
    static const SharedObjectClassSpec& get(int classId);

    static jclass javaClassOf(int classId);

    static const std::string& descriptorOf(int classId);

    static std::string nameOf(int classId) noexcept;
  };
} // namespace expo::modules::v2::sharedobjects
