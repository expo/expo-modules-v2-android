#pragma once

#include <cstdint>
#include <memory>

#include <expo-modules-v2/records/RecordSchema.h>

namespace expo::modules::v2 {
  class RecordRegistry {
  public:
    static const RecordSchema& get(int32_t schemaId);
  };
}
