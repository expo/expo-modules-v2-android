#pragma once

#include <cstdint>

#include <expo-modules-v2/unions/UnionSchema.h>

namespace expo::modules::v2 {
  class UnionRegistry {
  public:
    static const UnionSchema& get(int32_t unionId);

  private:
    static const UnionSchema& getLocked(int32_t unionId);
  };
}
