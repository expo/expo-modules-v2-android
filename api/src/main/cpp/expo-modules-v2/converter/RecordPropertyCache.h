#pragma once

#include <unordered_map>
#include <vector>

#include <jsi/jsi.h>

#include <expo-modules-v2/records/RecordSchema.h>

namespace expo::modules::v2 {
  struct RecordAccessPlan {
    const RecordSchema* schema;
    std::vector<facebook::jsi::PropNameID> fieldNames;
  };

  class RecordPropertyCache {
  public:
    [[nodiscard]] static RecordPropertyCache& get(facebook::jsi::Runtime& rt);

    static void clearForRuntime(const facebook::jsi::Runtime& rt) noexcept;

    [[nodiscard]] const RecordAccessPlan& planFor(
      facebook::jsi::Runtime& rt,
      int32_t schemaId
    );

  private:
    static RecordPropertyCache& threadLocal();

    void activate(facebook::jsi::Runtime& rt);

    void clear() noexcept;

    facebook::jsi::Runtime* runtime_ = nullptr;
    std::unordered_map<int32_t, RecordAccessPlan> plansBySchema_;
  };
}
