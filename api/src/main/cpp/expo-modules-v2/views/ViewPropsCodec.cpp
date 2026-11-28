#include <expo-modules-v2/views/ViewPropsCodec.h>

#include <exception>

#include <kolibri/binary/BinaryBuffer.h>
#include <kolibri/binary/BinaryReader.h>

#include <expo-modules-v2/converter/encoders/BufferEncode.h>
#include <expo-modules-v2/decoders/ExpectedTypeDecoder.h>
#include <expo-modules-v2/utils/Log.h>
#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2::views {
  ViewPropsCodec::ViewPropsCodec(JNIEnv* env, jobject decoder) {
    const auto payloadSize = static_cast<size_t>(JViewPropsDecoder::encodeSpec(env, decoder));

    const kolibri::binary::BinaryBuffer::Claim claim;
    if (!claim) {
      throwRuntimeError({"The binary bridge buffer is already in use"});
    }
    kolibri::binary::Reader reader = claim.reader(payloadSize);

    const size_t count = reader.readCount();
    names_.reserve(count);
    types_.reserve(count);
    for (size_t i = 0; i < count; i++) {
      names_.push_back(reader.readString());
      types_.push_back(decoders::decodeExpectedType(reader, /* allowBufferedHead */ true));
    }
    if (!reader.isOnEnd()) {
      throwInvalidArgument({"Trailing bytes in the view props payload"});
    }

    // Keys view into `names_`, which no longer grows.
    indexes_.reserve(count);
    for (size_t i = 0; i < count; i++) {
      indexes_.emplace(names_[i], i);
    }
  }

  std::optional<size_t> ViewPropsCodec::indexOf(std::string_view name) const {
    const auto entry = indexes_.find(name);
    if (entry == indexes_.end()) {
      return std::nullopt;
    }
    return entry->second;
  }

  EncodedViewProps::Shared ViewPropsCodec::encode(
    facebook::jsi::Runtime& rt,
    const std::vector<std::pair<size_t, facebook::jsi::Value>>& values,
    const EncodedViewProps::Shared& previous
  ) const {
    if (values.empty()) {
      return nullptr;
    }

    std::vector<EncodedViewProps::SharedSegment> segments =
      previous ? previous->segments() : std::vector<EncodedViewProps::SharedSegment>(names_.size());
    std::vector<int64_t> revisions = previous ? previous->revisions() : std::vector<int64_t>(names_.size(), 0);
    const int64_t revision = EncodedViewProps::nextRevision();
    bool changed = false;

    for (const auto& [index, value] : values) {
      if (value.isNull() || value.isUndefined()) {
        segments[index] = EncodedViewProps::resetSegment();
        revisions[index] = revision;
        changed = true;
        continue;
      }

      // The committing thread's bridge buffer is scratch space: each value is encoded into it and
      // copied out, because the bytes must outlive the commit.
      const kolibri::binary::BinaryBuffer::Claim claim;
      if (!claim) {
        logLine("View prop '" + names_[index] + "' was not applied: the bridge buffer is in use");
        continue;
      }
      kolibri::binary::BinaryBuffer& scratch = claim.buffer();
      scratch.clear();
      try {
        encodeToBuffer(rt, value, types_[index], scratch);
      } catch (const std::exception& error) {
        // As on iOS: a value that doesn't encode changes no prop. A later update can still set it.
        logLine("View prop '" + names_[index] + "' was not applied: " + error.what());
        continue;
      }
      segments[index] = std::make_shared<const EncodedViewProps::Segment>(EncodedViewProps::Segment{
        .reset = false,
        .bytes = std::vector<uint8_t>(scratch.data(), scratch.data() + scratch.size()),
      });
      revisions[index] = revision;
      changed = true;
    }

    if (!changed) {
      return nullptr;
    }
    return EncodedViewProps::create(std::move(segments), std::move(revisions));
  }

  EncodedViewProps::Shared ViewPropsCodec::encodeObject(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Object& props,
    const EncodedViewProps::Shared& previous
  ) const {
    const facebook::jsi::Array names = props.getPropertyNames(rt);
    const size_t count = names.size(rt);

    std::vector<std::pair<size_t, facebook::jsi::Value>> values;
    for (size_t i = 0; i < count; i++) {
      const facebook::jsi::String name = names.getValueAtIndex(rt, i).getString(rt);
      if (const auto index = indexOf(name.utf8(rt))) {
        values.emplace_back(*index, props.getProperty(rt, name));
      }
    }
    return encode(rt, values, previous);
  }
} // namespace expo::modules::v2::views
