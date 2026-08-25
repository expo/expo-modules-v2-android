#include <expo-modules-v2/converter/RecordPropertyCache.h>

#include <expo-modules-v2/records/RecordRegistry.h>

namespace expo::modules::v2 {
  RecordPropertyCache& RecordPropertyCache::threadLocal() {
    thread_local RecordPropertyCache cache;
    return cache;
  }

  RecordPropertyCache& RecordPropertyCache::get(facebook::jsi::Runtime& rt) {
    RecordPropertyCache& cache = threadLocal();
    cache.activate(rt);
    return cache;
  }

  void RecordPropertyCache::clearForRuntime(const facebook::jsi::Runtime& rt) noexcept {
    RecordPropertyCache& cache = threadLocal();
    if (cache.runtime_ == &rt) {
      cache.clear();
    }
  }

  void RecordPropertyCache::activate(facebook::jsi::Runtime& rt) {
    if (runtime_ == &rt) {
      return;
    }

    clear();
    runtime_ = &rt;
  }

  void RecordPropertyCache::clear() noexcept {
    plansBySchema_.clear();
    runtime_ = nullptr;
  }

  const RecordAccessPlan& RecordPropertyCache::planFor(
    facebook::jsi::Runtime& rt,
    const int32_t schemaId
  ) {
    activate(rt);

    if (const auto cached = plansBySchema_.find(schemaId); cached != plansBySchema_.end()) {
      return cached->second;
    }

    const RecordSchema& schema = RecordRegistry::get(schemaId);
    std::vector<facebook::jsi::PropNameID> names;
    names.reserve(schema.fields.size());
    for (const RecordFieldSpec& field: schema.fields) {
      names.emplace_back(facebook::jsi::PropNameID::forUtf8(rt, field.name));
    }

    return plansBySchema_.emplace(
      schemaId,
      RecordAccessPlan{
        .schema = &schema,
        .fieldNames = std::move(names)
      }
    ).first->second;
  }
}
