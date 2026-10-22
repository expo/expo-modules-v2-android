#pragma once

#include <memory>
#include <string_view>
#include <vector>

#include <jsi/jsi.h>

#include <expo-modules-v2/converter/AsciiKeyCache.h>
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

    [[nodiscard]] const facebook::jsi::PropNameID* asciiKey(
      facebook::jsi::Runtime& rt,
      std::string_view key
    );

  private:
    static RecordPropertyCache& threadLocal();

    void activate(facebook::jsi::Runtime& rt);

    void clear() noexcept;

    [[nodiscard]] const RecordAccessPlan& createPlan(facebook::jsi::Runtime& rt, int32_t schemaId);

    facebook::jsi::Runtime* runtime_ = nullptr;
    std::vector<std::unique_ptr<RecordAccessPlan>> plansBySchema_;
    AsciiKeyCache asciiKeys_;
  };
}
