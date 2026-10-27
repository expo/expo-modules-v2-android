#pragma once

#include <jni.h>

#include <utility>

#include <kolibri/defines.h>
#include <kolibri/env.h>

namespace expo::modules::v2::objects {
  struct SlotChunk;

  /**
   * A strong reference to a Kotlin instance JavaScript holds, which does not have to cost a JNI
   * global ref of its own.
   */
  class InstanceRef {
  public:
    InstanceRef() = default;

    ~InstanceRef() { reset(); }

    InstanceRef(InstanceRef&& other) noexcept
      : global_(std::exchange(other.global_, nullptr)),
        chunk_(std::exchange(other.chunk_, nullptr)),
        index_(std::exchange(other.index_, 0)),
        budgeted_(std::exchange(other.budgeted_, false)) {
    }

    InstanceRef& operator=(InstanceRef&& other) noexcept {
      if (this != &other) {
        reset();
        global_ = std::exchange(other.global_, nullptr);
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

    /** Pins [instance] by a global ref while the budget lasts, and in a slot after that. */
    static InstanceRef pin(JNIEnv* env, jobject instance);

    explicit operator bool() const { return global_ != nullptr || chunk_ != nullptr; }

    /** The global ref, or null when the instance sits in a slot or was released. */
    [[nodiscard]] jobject global() const { return global_; }

    /** A new local ref to the instance, which the caller deletes. Null once released. */
    [[nodiscard]] jobject newLocal(JNIEnv* env) const {
      if (global_ != nullptr) [[likely]] {
        return env->NewLocalRef(global_);
      }
      return readSlot(env);
    }

    /** Lets the instance go. Attaches the calling thread if it has to: see the definition. */
    void reset();

    /** How many instances hold a global ref out of the budget right now. */
    [[nodiscard]] static size_t budgetedGlobalCount();

    /** How many instances sit in a slot right now. */
    [[nodiscard]] static size_t slottedCount();

    /** How many chunks of slots are alive right now, the spare included. */
    [[nodiscard]] static size_t slotChunkCount();

  private:
    /** `newLocal` for an instance in a slot, or null if there is none. */
    [[nodiscard]] jobject readSlot(JNIEnv* env) const;

    jobject global_ = nullptr;
    /** The chunk holding the instance, or null when it holds a global ref or was released. */
    SlotChunk* chunk_ = nullptr;
    jsize index_ = 0;
    bool budgeted_ = false;
  };

  /**
   * The instance behind an `InstanceRef` for the length of one call: the global ref, borrowed, or
   * a local ref read out of the instance's slot and deleted when the call is over. Null once
   * released.
   */
  class ScopedInstance {
  public:
    explicit ScopedInstance(const InstanceRef& ref) : instance_(ref.global()) {
      if (instance_ == nullptr && ref) [[unlikely]] {
        readSlot(ref);
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
    NOINLINE void readSlot(const InstanceRef& ref) {
      env_ = kolibri::getEnv();
      instance_ = ref.newLocal(env_);
    }

    jobject instance_;
    JNIEnv* env_ = nullptr;
  };
} // namespace expo::modules::v2::objects
