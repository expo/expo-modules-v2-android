#include <expo-modules-v2/converter/RecordPropertyCache.h>

#include <string>

#include <expo-modules-v2/records/RecordRegistry.h>
#include <expo-modules-v2/utils/Throw.h>

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
    asciiKeys_.clear();
    runtime_ = nullptr;
  }

  const facebook::jsi::PropNameID* RecordPropertyCache::asciiKey(
    facebook::jsi::Runtime& rt,
    const std::string_view key
  ) {
    activate(rt);
    return asciiKeys_.get(rt, key);
  }

  const RecordAccessPlan& RecordPropertyCache::planFor(
    facebook::jsi::Runtime& rt,
    const int32_t schemaId
  ) {
    activate(rt);

    const size_t index = static_cast<size_t>(schemaId);
    const bool hasPlan = index < plansBySchema_.size() && plansBySchema_[index] != nullptr;
    if (hasPlan) [[likely]] {
      return *plansBySchema_[index];
    }
    return createPlan(rt, schemaId);
  }

  const RecordAccessPlan& RecordPropertyCache::createPlan(
    facebook::jsi::Runtime& rt,
    const int32_t schemaId
  ) {
    if (schemaId < 0) {
      throwInvalidArgument({"Invalid record schema id ", std::to_string(schemaId)});
    }

    const RecordSchema& schema = RecordRegistry::get(schemaId);
    std::vector<facebook::jsi::PropNameID> names;
    names.reserve(schema.fields.size());
    for (const RecordFieldSpec& field: schema.fields) {
      names.emplace_back(facebook::jsi::PropNameID::forUtf8(rt, field.name));
    }

    const auto index = static_cast<size_t>(schemaId);
    if (index >= plansBySchema_.size()) {
      plansBySchema_.resize(index + 1);
    }
    plansBySchema_[index] = std::make_unique<RecordAccessPlan>(RecordAccessPlan{
      .schema = &schema,
      .fieldNames = std::move(names)
    });
    return *plansBySchema_[index];
  }
}
