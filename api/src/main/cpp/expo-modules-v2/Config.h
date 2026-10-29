#pragma once

#include <cstddef>

#include <jni.h>

/**
 * The bridge's tuning knobs, in one place. Every value is a compile-time constant read straight by
 * the code it tunes, so editing one here and rebuilding is all a change takes - unless its comment
 * names a Kotlin counterpart, which has to change with it.
 *
 * The `static_assert`s at the bottom reject values the code relies on never seeing.
 */
namespace expo::modules::v2::config {
  // --- Function calls ----------------------------------------------------------------------------

  /**
   * The most arguments a function or constructor exported to JavaScript may declare. A call stages
   * its arguments in stack arrays of this size and reserves this many JNI local references.
   *
   * MUST stay in sync with Kotlin `Trampoline.MAX_ARGUMENTS` (:api) and `Identifiers.MAX_ARGUMENTS`
   * (:compiler-plugin), which reject a declaration over the limit at run time and compile time.
   */
  inline constexpr size_t kMaxFunctionArgs = 8;

  // --- Strings -----------------------------------------------------------------------------------

  /**
   * Java strings up to this many UTF-16 units become JS strings through a stack buffer, with one
   * `GetStringRegion`. A longer one is copied out by whichever JNI call suits its text.
   *
   * The buffer takes 3 bytes per unit of stack (the units, and their ASCII narrowing).
   */
  inline constexpr jsize kJavaStringStackUnits = 1024;

  /**
   * How many leading units of a Java string longer than [kJavaStringStackUnits] are checked for
   * ASCII to decide whether it is worth asking JNI for its modified-UTF-8 length.
   */
  inline constexpr jsize kJavaStringAsciiProbeUnits = 32;

  /**
   * JS strings up to this many UTF-16 units become Java strings through a stack buffer, longer
   * ones through the heap.
   */
  inline constexpr size_t kJsStringStackUnits = 256;

  // --- Property names ----------------------------------------------------------------------------

  /**
   * How many ASCII property names each runtime keeps as `PropNameID`s, least recently used evicted
   * first. One slot stays free (see `AsciiKeyCache`), so the cache holds one name fewer.
   */
  inline constexpr size_t kPropertyNameCacheSlots = 256;

  /**
   * The longest property name the cache takes. A longer one is made anew on every access. It also
   * sizes the stack buffers a property name from Kotlin is copied into, 3 bytes per unit.
   */
  inline constexpr size_t kMaxCachedPropertyNameLength = 64;

  // --- Arrays ------------------------------------------------------------------------------------

  /**
   * How many values a JS array being built stages inline, on the stack, before pushing them. An
   * array whose length is known up front and larger than this stages on the heap instead.
   */
  inline constexpr size_t kJsArrayInlineValues = 64;

  /** The most values a JS array being built stages on the heap at once: 16K values, 256 KiB. */
  inline constexpr size_t kJsArrayMaxBufferedValues = 16 * 1024;

  // --- Async -------------------------------------------------------------------------------------

  /** How many Kotlin promise handles one call into Kotlin makes. */
  inline constexpr size_t kPromiseHandleBatch = 32;

  // --- Objects -----------------------------------------------------------------------------------

  /**
   * How many objects a runtime tracks before it first sweeps the ones JavaScript dropped. After a
   * sweep the next one waits until the count doubles, but never for fewer than this.
   */
  inline constexpr size_t kObjectSweepMinThreshold = 32;

  /**
   * How many shared objects pin their Kotlin instance with a JNI global ref of their own, which a
   * call borrows as its receiver for free. Android aborts the process at 51,200 global refs, held by
   * every library together, so this is 2% of that. Every object sits in a slot of a shared Java
   * array, and the first call on one promotes it to a global ref while this lasts; a call on one
   * that was not costs two more JNI calls (see `InstanceRef`).
   */
  inline constexpr size_t kSharedObjectGlobalRefs = 1024;

  /**
   * How many slotted instances share one Java array, and so one global ref. A new array is made
   * when every slot so far is taken, and one is freed once none of its slots is, all but one
   * spare.
   */
  inline constexpr jsize kInstanceSlotsPerChunk = 1024;

  // --- Sanity checks -----------------------------------------------------------------------------

  static_assert(kMaxFunctionArgs > 0);
  static_assert(kJavaStringStackUnits > 0);
  // The probe reads its units from a string longer than the stack buffer.
  static_assert(kJavaStringAsciiProbeUnits > 0);
  static_assert(kJavaStringAsciiProbeUnits <= kJavaStringStackUnits);
  static_assert(kJsStringStackUnits > 0);
  // The cache evicts by adding the new name before dropping the old one, so it needs a free slot.
  static_assert(kPropertyNameCacheSlots >= 2);
  static_assert(kMaxCachedPropertyNameLength > 0);
  static_assert(kJsArrayInlineValues > 0);
  static_assert(kJsArrayMaxBufferedValues >= kJsArrayInlineValues);
  static_assert(kPromiseHandleBatch > 0);
  static_assert(kObjectSweepMinThreshold > 0);
  static_assert(kInstanceSlotsPerChunk > 0);
}
