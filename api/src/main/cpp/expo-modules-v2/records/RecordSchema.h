#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2 {
  struct RecordFieldSpec {
    std::string name;
    ExpectedType type;
    bool optional = false;
  };

  struct RecordSchema {
    int32_t id;
    std::string name;
    std::string jniDescriptor;
    bool bufferSafe = true;
    std::vector<RecordFieldSpec> fields;
  };
}
