#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include <kolibri/binary/BinaryBuffer.h>

namespace expo::modules::v2::views {
  /**
   * PROTOTYPE. The view's own props of one props revision, each encoded in the bridge's binary format
   * by the thread that committed them. Immutable: an update makes a new instance that shares the
   * segments of the props it didn't change.
   *
   * The props object Fabric gives Java carries only this object's [handle]. Java turns it back into the
   * object with [lookup] when the UI thread mounts the props, and reads the changed props out of it.
   * The table that resolves handles holds weak references, so an instance lives exactly as long as the
   * Fabric props that own it: when a handle no longer resolves, a later commit replaced those props,
   * and the mount item of that commit carries the newer handle.
   */
  class EncodedViewProps final {
  public:
    /** One prop's value in the binary format, or a reset to the prop's default (`null`/`undefined`). */
    struct Segment {
      bool reset = false;
      std::vector<uint8_t> bytes;
    };

    using Shared = std::shared_ptr<const EncodedViewProps>;
    using SharedSegment = std::shared_ptr<const Segment>;

    /** The tag of an entry that carries a value; one that resets carries no bytes. */
    static constexpr uint8_t kEntryValue = 1;
    static constexpr uint8_t kEntryReset = 0;

    /**
     * Makes an instance and registers it under a new handle. [segments] has one slot per prop of the
     * view, null for a prop that was never set, and [revisions] the revision each prop last changed in
     * (0 when never set).
     */
    static Shared create(std::vector<SharedSegment> segments, std::vector<int64_t> revisions);

    /** The instance registered under [handle], or null when it is gone. Safe on any thread. */
    static Shared lookup(int64_t handle);

    /** A segment that resets a prop. Shared by every reset. */
    static const SharedSegment& resetSegment();

    /** A new revision, unique across every view and every update. */
    static int64_t nextRevision();

    ~EncodedViewProps();

    EncodedViewProps(const EncodedViewProps&) = delete;
    EncodedViewProps& operator=(const EncodedViewProps&) = delete;

    [[nodiscard]] int64_t handle() const { return handle_; }

    [[nodiscard]] const std::vector<SharedSegment>& segments() const { return segments_; }

    [[nodiscard]] const std::vector<int64_t>& revisions() const { return revisions_; }

    /**
     * Writes the props whose revision differs from [mounted] (the revisions the view has, one per prop,
     * or null for a view that has none) into [out], from its current size:
     *
     *   count: i32, count x { index: i32, revision: i64, tag: u8, [value] }
     *
     * where `value`, present when `tag` is [kEntryValue], is the prop's value in the binary format the
     * prop's Kotlin converter reads with `readFromBuffer`. Returns the number of entries.
     */
    size_t writeChanges(kolibri::binary::BinaryBuffer& out, const int64_t* mounted, size_t mountedCount) const;

  private:
    EncodedViewProps(int64_t handle, std::vector<SharedSegment> segments, std::vector<int64_t> revisions);

    const int64_t handle_;
    const std::vector<SharedSegment> segments_;
    const std::vector<int64_t> revisions_;
  };
} // namespace expo::modules::v2::views
