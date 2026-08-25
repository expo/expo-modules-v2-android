#include <expo-modules-v2/jni/JRecordSchemaData.h>

#include <utility>

#include <kolibri/binary/BinaryReader.h>

#include <expo-modules-v2/decoders/ExpectedTypeDecoder.h>

namespace expo::modules::v2 {
  std::string JRecordSchemaData::Accessors::name(JNIEnv* env) const {
    return name_(env, handle_)->toStdString(env);
  }

  std::string JRecordSchemaData::Accessors::jniDescriptor(JNIEnv* env) const {
    return jniDescriptor_(env, handle_)->toStdString(env);
  }

  bool JRecordSchemaData::Accessors::isBufferSafe(JNIEnv* env) const {
    return bufferSafe_(env, handle_);
  }

  std::vector<RecordFieldSpec> JRecordSchemaData::Accessors::fields(JNIEnv* env) const {
    const auto fieldNames = fieldNames_(env, handle_);
    const auto fieldTypes = fieldTypes_(env, handle_);
    const std::vector<jboolean> fieldOptional = fieldOptional_(env, handle_)->toVector(env);

    const kolibri::PinnedArray<const jint> codes{
      env,
      static_cast<jintArray>(fieldTypes.get())
    };

    const auto* begin = reinterpret_cast<const uint8_t*>(codes.data());
    kolibri::binary::Reader reader{
      .position = begin,
      .end = begin + static_cast<size_t>(codes.size()) * sizeof(jint)
    };

    const jsize fieldCount = fieldNames->size(env);

    std::vector<RecordFieldSpec> result;
    result.reserve(fieldCount);

    for (jsize i = 0; i < fieldCount; i++) {
      auto fieldName = fieldNames->getUnsafeElement(env, i)->toStdString(env);
      result.push_back(
        RecordFieldSpec{
          .name = std::move(fieldName),
          .type = decoders::decodeExpectedType(reader),
          .optional = fieldOptional[i] != JNI_FALSE,
        }
      );
    }

    if (reader.position != reader.end) {
      throw std::runtime_error("Trailing type codes in a record schema");
    }

    return result;
  }
} // namespace expo::modules::v2
