#include <expo-modules-v2/objects/InstanceRef.h>

#include <array>
#include <atomic>
#include <mutex>

#include <expo-modules-v2/Config.h>
#include <kolibri/class.h>

namespace expo::modules::v2::objects {
  namespace {
    constexpr size_t kGlobalBudget = config::kSharedObjectGlobalRefs;
    constexpr jsize kChunkSize = config::kInstanceSlotsPerChunk;
  } // namespace

  /**
   * One Java `Object[]` of slots, held by a global ref, and the free slots in it. A chunk is made
   * when no other has a free slot and freed once none of its slots is taken, so while a ref points
   * at its chunk the chunk is alive and the ref reads it without the lock.
   */
  struct SlotChunk {
    struct Link {
      SlotChunk* prev = nullptr;
      SlotChunk* next = nullptr;
    };

    jobjectArray array;
    /** Neighbours in the list of chunks with both taken and free slots. */
    Link partial;
    /** The free slots' indices, as a stack: `free[0, freeCount)`. */
    std::array<jsize, kChunkSize> free;
    jsize freeCount;
  };

  namespace {
    std::atomic<size_t>& budgetedGlobals() {
      static std::atomic<size_t> count{0};
      return count;
    }

    struct Slot {
      SlotChunk* chunk = nullptr;
      jsize index = 0;
    };

    struct PartialChunks {
      SlotChunk* head = nullptr;

      void push(SlotChunk* chunk) {
        chunk->partial = {
          .prev = nullptr,
          .next = head
        };

        if (head != nullptr) {
          head->partial.prev = chunk;
        }

        head = chunk;
      }

      void remove(SlotChunk* chunk) {
        const SlotChunk::Link link = std::exchange(chunk->partial, {});
        if (link.prev != nullptr) {
          link.prev->partial.next = link.next;
        } else {
          head = link.next;
        }
        if (link.next != nullptr) {
          link.next->partial.prev = link.prev;
        }
      }
    };

    /** The slots past the budget; the lock guards handing them out and taking them back. */
    struct Slots {
      std::mutex mutex;
      /** The chunks to take a slot from first, so the others can fill up or empty out. */
      PartialChunks partial;
      /**
       * One chunk with no slot taken, kept rather than freed, so objects coming and going around a
       * chunk's worth do not make and free a Java array each time.
       */
      SlotChunk* spare = nullptr;
      size_t chunks = 0;
      size_t used = 0;
    };

    Slots& slots() {
      NO_DESTROY static Slots table;
      return table;
    }

    SlotChunk* makeChunk(JNIEnv* env) {
      const jclass objectClass = kolibri::JObject::javaClass();
      const jobjectArray local = env->NewObjectArray(kChunkSize, objectClass, nullptr);

      auto* chunk = new SlotChunk{
        .array = static_cast<jobjectArray>(env->NewGlobalRef(local)),
        .partial = {},
        .free = {},
        .freeCount = kChunkSize,
      };
      // Popped from the back, so the slots are handed out from index 0 up.
      for (jsize i = 0; i < kChunkSize; i++) {
        chunk->free[i] = kChunkSize - 1 - i;
      }

      env->DeleteLocalRef(local);
      return chunk;
    }

    void freeChunk(JNIEnv* env, const SlotChunk* chunk) {
      env->DeleteGlobalRef(chunk->array);
      delete chunk;
    }

    /** A free slot: from a chunk in use if there is one, else the spare, else a new chunk. */
    Slot takeSlot(JNIEnv* env) {
      Slots& table = slots();
      const std::lock_guard lock(table.mutex);

      SlotChunk* chunk = table.partial.head;
      if (chunk == nullptr) {
        chunk = std::exchange(table.spare, nullptr);
        if (chunk == nullptr) {
          chunk = makeChunk(env);
          table.chunks++;
        }
        table.partial.push(chunk);
      }

      const jsize index = chunk->free[--chunk->freeCount];
      if (chunk->freeCount == 0) {
        table.partial.remove(chunk);
      }

      table.used++;
      return {
        .chunk = chunk,
        .index = index
      };
    }

    void giveSlotBack(JNIEnv* env, const Slot slot) {
      Slots& table = slots();
      const std::lock_guard lock(table.mutex);

      SlotChunk* chunk = slot.chunk;
      chunk->free[chunk->freeCount++] = slot.index;
      table.used--;

      if (chunk->freeCount == 1) {
        // It was full.
        table.partial.push(chunk);
      }
      if (chunk->freeCount == kChunkSize) {
        // Nothing points at it any more.
        table.partial.remove(chunk);
        if (table.spare == nullptr) {
          table.spare = chunk;
        } else {
          freeChunk(env, chunk);
          table.chunks--;
        }
      }
    }
  } // namespace

  InstanceRef InstanceRef::global(JNIEnv* env, const jobject instance) {
    InstanceRef ref;
    ref.global_.store(env->NewGlobalRef(instance), std::memory_order_relaxed);
    return ref;
  }

  InstanceRef InstanceRef::pin(JNIEnv* env, const jobject instance) {
    InstanceRef ref;
    const Slot slot = takeSlot(env);
    env->SetObjectArrayElement(slot.chunk->array, slot.index, instance);
    ref.chunk_ = slot.chunk;
    ref.index_ = slot.index;
    ref.budgeted_ = true;
    return ref;
  }

  void InstanceRef::promote(JNIEnv* env, const jobject instance) const {
    // Read first, so an object past a spent budget pays one load per call and no write.
    std::atomic<size_t>& budget = budgetedGlobals();
    if (!budgeted_ || budget.load(std::memory_order_relaxed) >= kGlobalBudget) {
      return;
    }
    if (budget.fetch_add(1, std::memory_order_relaxed) >= kGlobalBudget) {
      budget.fetch_sub(1, std::memory_order_relaxed);
      return;
    }

    const jobject global = env->NewGlobalRef(instance);
    jobject expected = nullptr;
    if (!global_.compare_exchange_strong(expected, global, std::memory_order_release, std::memory_order_relaxed)) {
      // Another thread promoted it first.
      env->DeleteGlobalRef(global);
      budget.fetch_sub(1, std::memory_order_relaxed);
    }
  }

  jobject InstanceRef::readSlot(JNIEnv* env) const {
    if (chunk_ == nullptr) {
      return nullptr;
    }
    return env->GetObjectArrayElement(chunk_->array, index_);
  }

  void InstanceRef::reset() {
    if (!*this) {
      return;
    }

    // The last owner may be a JavaScript object Hermes finalizes on a JNI-detached thread, so
    // attach here in case this is that thread.
    JNIEnv* env = kolibri::getEnv();

    if (const jobject global = global_.exchange(nullptr, std::memory_order_relaxed); global != nullptr) {
      env->DeleteGlobalRef(global);
      if (budgeted_) {
        budgetedGlobals().fetch_sub(1, std::memory_order_relaxed);
      }
    }
    budgeted_ = false;
    if (chunk_ == nullptr) {
      return;
    }

    const Slot slot{
      .chunk = std::exchange(chunk_, nullptr),
      .index = std::exchange(index_, 0)
    };
    env->SetObjectArrayElement(slot.chunk->array, slot.index, nullptr);
    giveSlotBack(env, slot);
  }

  size_t InstanceRef::budgetedGlobalCount() {
    return budgetedGlobals().load(std::memory_order_relaxed);
  }

  size_t InstanceRef::slottedCount() {
    Slots& table = slots();
    const std::lock_guard lock(table.mutex);
    return table.used;
  }

  size_t InstanceRef::slotChunkCount() {
    Slots& table = slots();
    const std::lock_guard lock(table.mutex);
    return table.chunks;
  }
} // namespace expo::modules::v2::objects
