#include <expo-modules-v2/unions/UnionRegistry.h>

#include <memory>
#include <mutex>
#include <unordered_map>
#include <utility>

#include <kolibri/env.h>
#include <kolibri/defines.h>

#include <expo-modules-v2/jni/JUnionRegistry.h>

namespace expo::modules::v2 {
  namespace {
    std::mutex& registryMutex() {
      NO_DESTROY static std::mutex mutex;
      return mutex;
    }

    std::unordered_map<int32_t, std::unique_ptr<const UnionSchema>>& registryMap() {
      NO_DESTROY static std::unordered_map<int32_t, std::unique_ptr<const UnionSchema>> map;
      return map;
    }
  }

  const UnionSchema& UnionRegistry::get(int32_t unionId) {
    // Every conversion of a union looks its schema up. A schema is never removed and never moves,
    // so each thread keeps the ones it has seen and takes the lock only on its first sight of one.
    thread_local std::unordered_map<int32_t, const UnionSchema*> seen;
    if (const auto it = seen.find(unionId); it != seen.end()) [[likely]] {
      return *it->second;
    }

    const UnionSchema& schema = getLocked(unionId);
    seen.emplace(unionId, &schema);
    return schema;
  }

  const UnionSchema& UnionRegistry::getLocked(int32_t unionId) {
    const std::lock_guard lock(registryMutex());
    const auto it = registryMap().find(unionId);
    if (it != registryMap().end()) {
      return *it->second;
    }

    std::unique_ptr<const UnionSchema> schema = JUnionRegistry::fetchSchema(
      kolibri::getEnv(),
      unionId
    );

    const auto entry = registryMap().emplace(unionId, std::move(schema)).first;
    return *entry->second;
  }
}
