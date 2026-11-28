#pragma once

#include <cstddef>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

#include <jni.h>
#include <jsi/jsi.h>

#include <kolibri/JavaClass.h>

#include <expo-modules-v2/descriptor/ExpectedType.h>
#include <expo-modules-v2/views/EncodedViewProps.h>

namespace expo::modules::v2::views {
  /** `io.github.expo.modules.v2.views.ViewPropsDecoder`: describes a view's props to native code. */
  struct JViewPropsDecoder : kolibri::JavaClass<JViewPropsDecoder> {
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/views/ViewPropsDecoder";

    static constexpr Method<"encodeSpec", jint()> encodeSpec{};
  };

  /**
   * PROTOTYPE. Encodes the props of one view type: picks the view's own props out of a props object and
   * encodes each into the bridge's binary format with the type the Kotlin decoder declared for it.
   *
   * Encoding needs no JNI: it runs on the thread that commits the props, and the JVM only sees the
   * bytes when the UI thread mounts them. Knows nothing about React Native, so desktop tests run the same
   * code against Hermes. Holds no state past construction and is safe on any thread.
   */
  class ViewPropsCodec {
  public:
    /** Reads the prop names and types from [decoder], a Kotlin `ViewPropsDecoder`. */
    ViewPropsCodec(JNIEnv* env, jobject decoder);

    ViewPropsCodec(const ViewPropsCodec&) = delete;
    ViewPropsCodec& operator=(const ViewPropsCodec&) = delete;

    [[nodiscard]] size_t propCount() const { return names_.size(); }

    [[nodiscard]] const std::string& nameAt(size_t index) const { return names_[index]; }

    [[nodiscard]] const ExpectedType& typeAt(size_t index) const { return types_[index]; }

    /** The index of the prop named [name], or nullopt when it isn't one of the view's props. */
    [[nodiscard]] std::optional<size_t> indexOf(std::string_view name) const;

    /**
     * Encodes [values], each the value of the prop at its index, on top of [previous] (null before the
     * first update). `null` and `undefined` reset a prop to its default; a value that doesn't encode
     * changes nothing. Returns null when nothing changed, so the caller keeps [previous].
     */
    [[nodiscard]] EncodedViewProps::Shared encode(
      facebook::jsi::Runtime& rt,
      const std::vector<std::pair<size_t, facebook::jsi::Value>>& values,
      const EncodedViewProps::Shared& previous
    ) const;

    /** Picks the view's props out of [props] and encodes them like [encode]. */
    [[nodiscard]] EncodedViewProps::Shared encodeObject(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& props,
      const EncodedViewProps::Shared& previous
    ) const;

  private:
    std::vector<std::string> names_;
    std::vector<ExpectedType> types_;
    std::unordered_map<std::string_view, size_t> indexes_;
  };
} // namespace expo::modules::v2::views
