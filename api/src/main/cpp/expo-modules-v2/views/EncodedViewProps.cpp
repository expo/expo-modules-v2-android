#include <expo-modules-v2/views/EncodedViewProps.h>

#include <atomic>
#include <mutex>
#include <unordered_map>
#include <utility>

namespace expo::modules::v2::views {
  namespace {
    /**
     * Handle -> instance. Leaked on purpose: instances can be released by threads that outlive static
     * destruction at process exit.
     */
    struct Registry {
      std::mutex mutex;
      std::unordered_map<int64_t, std::weak_ptr<const EncodedViewProps>> entries;
    };

    Registry& registry() {
      static auto* instance = new Registry();
      return *instance;
    }

    std::atomic<int64_t> nextHandle{1};
    std::atomic<int64_t> revisionCounter{0};
  } // namespace

  EncodedViewProps::EncodedViewProps(
    const int64_t handle,
    std::vector<SharedSegment> segments,
    std::vector<int64_t> revisions
  )
    : handle_(handle), segments_(std::move(segments)), revisions_(std::move(revisions)) {}

  EncodedViewProps::Shared EncodedViewProps::create(
    std::vector<SharedSegment> segments,
    std::vector<int64_t> revisions
  ) {
    const int64_t handle = nextHandle.fetch_add(1, std::memory_order_relaxed);
    Shared instance(new EncodedViewProps(handle, std::move(segments), std::move(revisions)));

    Registry& table = registry();
    std::lock_guard lock(table.mutex);
    table.entries.emplace(handle, instance);
    return instance;
  }

  EncodedViewProps::~EncodedViewProps() {
    // A lookup that races this destructor finds the weak reference already expired, so it can't
    // revive the instance; erasing only keeps the table small.
    Registry& table = registry();
    std::lock_guard lock(table.mutex);
    table.entries.erase(handle_);
  }

  EncodedViewProps::Shared EncodedViewProps::lookup(const int64_t handle) {
    Registry& table = registry();
    std::lock_guard lock(table.mutex);
    const auto entry = table.entries.find(handle);
    if (entry == table.entries.end()) {
      return nullptr;
    }
    return entry->second.lock();
  }

  const EncodedViewProps::SharedSegment& EncodedViewProps::resetSegment() {
    static const SharedSegment segment = std::make_shared<const Segment>(Segment{.reset = true, .bytes = {}});
    return segment;
  }

  int64_t EncodedViewProps::nextRevision() {
    return revisionCounter.fetch_add(1, std::memory_order_relaxed) + 1;
  }

  size_t EncodedViewProps::writeChanges(
    kolibri::binary::BinaryBuffer& out,
    const int64_t* mounted,
    const size_t mountedCount
  ) const {
    const size_t countOffset = out.size();
    out.write<int32_t>(0);

    size_t count = 0;
    for (size_t index = 0; index < segments_.size(); index++) {
      const int64_t mountedRevision = mounted != nullptr && index < mountedCount ? mounted[index] : 0;
      if (revisions_[index] == mountedRevision) {
        continue;
      }
      const Segment& segment = *segments_[index];
      out.write<int32_t>(static_cast<int32_t>(index));
      out.write<int64_t>(revisions_[index]);
      if (segment.reset) {
        out.write<uint8_t>(kEntryReset);
      } else {
        out.write<uint8_t>(kEntryValue);
        out.writeBytes(segment.bytes.data(), segment.bytes.size());
      }
      count++;
    }
    out.writeAt<int32_t>(countOffset, static_cast<int32_t>(count));
    return count;
  }
} // namespace expo::modules::v2::views
