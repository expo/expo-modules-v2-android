#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include <jni.h>

#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2 {
  struct RecordFieldSpec {
    std::string name;
    ExpectedType type;
    bool optional = false;
    // [name] as the Java string Kotlin registered, the key of the field in a record's map, so
    // building or reading that map makes no string per field. A global reference that is never
    // released: a schema is registered for the rest of the process, and outlives the JVM thread
    // that would have to release it.
    jstring javaName = nullptr;
  };

  struct RecordSchema {
    int32_t id;
    std::string name;
    std::string jniDescriptor;
    bool bufferSafe = true;
    std::vector<RecordFieldSpec> fields;
  };
}
