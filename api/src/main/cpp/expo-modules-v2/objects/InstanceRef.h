#pragma once

#include <jni.h>

#include <atomic>
#include <utility>

#include <kolibri/defines.h>
#include <kolibri/env.h>

namespace expo::modules::v2::objects {
  struct SlotChunk;

  /**
   * A strong reference to a Kotlin instance JavaScript holds, which does not have to cost a JNI
   * global ref of its own.
   *
   * A shared object starts out in a slot and is promoted to a global ref of its own the first time
   * it is called on, while the budget lasts: an object called once tends to be called again, and
   * most are never called at all. A promoted instance keeps its slot until it is let go, since a
   * call on another runtime's thread may be reading it out of there right then.
   */
  class InstanceRef {
  public:
    InstanceRef() = default;

    ~InstanceRef() { reset(); }

    InstanceRef(InstanceRef&& other) noexcept
      : global_(other.global_.exchange(nullptr, std::memory_order_relaxed)),
        chunk_(std::exchange(other.chunk_, nullptr)),
        index_(std::exchange(other.index_, 0)),
        budgeted_(std::exchange(other.budgeted_, false)) {
    }

    InstanceRef& operator=(InstanceRef&& other) noexcept {
      if (this != &other) {
        reset();
        global_.store(other.global_.exchange(nullptr, std::memory_order_relaxed), std::memory_order_relaxed);
        chunk_ = std::exchange(other.chunk_, nullptr);
        index_ = std::exchange(other.index_, 0);
        budgeted_ = std::exchange(other.budgeted_, false);
      }
      return *this;
    }

    InstanceRef(const InstanceRef&) = delete;

    InstanceRef& operator=(const InstanceRef&) = delete;

    /**
     * Pins [instance] by a global ref outside the budget. For the few instances whose receiver is
     * always borrowed as a global ref - modules - so never a shared object.
     */
    static InstanceRef global(JNIEnv* env, jobject instance);

    /** Pins [instance] in a slot, until `promote` gives it a global ref of its own. */
    static InstanceRef pin(JNIEnv* env, jobject instance);

    explicit operator bool() const { return global() != nullptr || chunk_ != nullptr; }

    /** The global ref, or null when the instance only sits in a slot or was released. */
    [[nodiscard]] jobject global() const { return global_.load(std::memory_order_acquire); }

    /** A new local ref to the instance, which the caller deletes. Null once released. */
    [[nodiscard]] jobject newLocal(JNIEnv* env) const {
      if (const jobject global = this->global(); global != nullptr) [[likely]] {
        return env->NewLocalRef(global);
      }
      return readSlot(env);
    }

    /**
     * Gives the slotted [instance], a local ref read out of this, a global ref of its own if the
     * budget has room for one; nothing otherwise, or if it already has one.
     */
    void promote(JNIEnv* env, jobject instance) const;

    /** Lets the instance go. Attaches the calling thread if it has to: see the definition. */
    void reset();

    /** How many instances were promoted to a global ref out of the budget and hold it right now. */
    [[nodiscard]] static size_t budgetedGlobalCount();

    /** How many instances sit in a slot right now. */
    [[nodiscard]] static size_t slottedCount();

    /** How many chunks of slots are alive right now, the spare included. */
    [[nodiscard]] static size_t slotChunkCount();

  private:
    /** `newLocal` for an instance in a slot, or null if there is none. */
    [[nodiscard]] jobject readSlot(JNIEnv* env) const;

    /** Set at most once after construction, by `promote`, which any thread may call. */
    mutable std::atomic<jobject> global_{nullptr};
    /** The chunk holding the instance, or null for a module or once released. */
    SlotChunk* chunk_ = nullptr;
    jsize index_ = 0;
    /** Whether the global ref, once there is one, counts against the budget. */
    bool budgeted_ = false;
  };

  /**
   * The instance behind an `InstanceRef` for the length of one call: the global ref, borrowed, or
   * a local ref read out of the instance's slot and deleted when the call is over. Null once
   * released.
   */
  class ScopedInstance {
  public:
    /** Whether reading the instance out of its slot also promotes it (`InstanceRef::promote`). */
    enum class Promote : bool { No, Yes };

    explicit ScopedInstance(const InstanceRef& ref, const Promote promote = Promote::No)
      : instance_(ref.global()) {
      if (instance_ == nullptr && ref) [[unlikely]] {
        readSlot(ref, promote);
      }
    }

    ~ScopedInstance() {
      if (env_ != nullptr && instance_ != nullptr) [[unlikely]] {
        env_->DeleteLocalRef(instance_);
      }
    }

    ScopedInstance(const ScopedInstance&) = delete;

    ScopedInstance& operator=(const ScopedInstance&) = delete;

    [[nodiscard]] jobject get() const { return instance_; }

  private:
    NOINLINE void readSlot(const InstanceRef& ref, const Promote promote) {
      env_ = kolibri::getEnv();
      instance_ = ref.newLocal(env_);
      if (promote == Promote::Yes && instance_ != nullptr) {
        ref.promote(env_, instance_);
      }
    }

    jobject instance_;
    JNIEnv* env_ = nullptr;
  };
} // namespace expo::modules::v2::objects
