#include <expo-modules-v2/records/RecordRegistry.h>

#include <mutex>
#include <unordered_map>
#include <utility>

#include <kolibri/env.h>

#include <expo-modules-v2/jni/JRecordRegistry.h>

namespace expo::modules::v2 {
  namespace {
    std::mutex& registryMutex() {
      static std::mutex mutex;
      return mutex;
    }

    std::unordered_map<int32_t, std::unique_ptr<const RecordSchema>>& registryMap() {
      static std::unordered_map<int32_t, std::unique_ptr<const RecordSchema>> map;
      return map;
    }
  }

  const RecordSchema& RecordRegistry::get(int32_t schemaId) {
    const std::lock_guard lock(registryMutex());
    const auto it = registryMap().find(schemaId);
    if (it != registryMap().end()) {
      return *it->second;
    }

    std::unique_ptr<const RecordSchema> schema = JRecordRegistry::fetchSchema(
      kolibri::getEnv(),
      schemaId
    );

    const auto entry = registryMap().emplace(schemaId, std::move(schema)).first;
    return *entry->second;
  }
}
