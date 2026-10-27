#include <expo-modules-v2/jni/JUnionSchemaData.h>

#include <utility>

#include <kolibri/binary/BinaryReader.h>

#include <expo-modules-v2/decoders/ExpectedTypeDecoder.h>
#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2 {
  std::string JUnionSchemaData::Accessors::name(JNIEnv* env) const {
    return name_(env, handle_)->toStdString(env);
  }

  std::optional<std::string> JUnionSchemaData::Accessors::discriminator(JNIEnv* env) const {
    const auto discriminator = discriminator_(env, handle_);
    if (!discriminator) {
      return std::nullopt;
    }
    return discriminator->toStdString(env);
  }

  bool JUnionSchemaData::Accessors::isBufferSafe(JNIEnv* env) const {
    return bufferSafe_(env, handle_);
  }

  std::vector<UnionVariantSpec> JUnionSchemaData::Accessors::variants(JNIEnv* env) const {
    const std::vector<jint> kinds = variantKinds_(env, handle_)->toVector(env);
    const auto tags = variantTags_(env, handle_);
    const auto types = variantTypes_(env, handle_);

    const kolibri::PinnedArray<const jint> codes{
      env,
      static_cast<jintArray>(types.get())
    };

    const auto* begin = reinterpret_cast<const uint8_t*>(codes.data());
    kolibri::binary::Reader reader{
      .position = begin,
      .end = begin + static_cast<size_t>(codes.size()) * sizeof(jint)
    };

    std::vector<UnionVariantSpec> result;
    result.reserve(kinds.size());

    for (size_t i = 0; i < kinds.size(); i++) {
      if (kinds[i] < 0 || static_cast<size_t>(kinds[i]) >= kUnionVariantKindCount) {
        throwRuntimeError({"Unknown union variant kind in a union schema"});
      }

      result.push_back(
        UnionVariantSpec{
          .kind = static_cast<UnionVariantKind>(kinds[i]),
          .tag = tags->getUnsafeElement(env, static_cast<jsize>(i))->toStdString(env),
          .type = decoders::decodeExpectedType(reader),
        }
      );
    }

    if (reader.position != reader.end) {
      throwRuntimeError({"Trailing type codes in a union schema"});
    }

    return result;
  }
} // namespace expo::modules::v2
