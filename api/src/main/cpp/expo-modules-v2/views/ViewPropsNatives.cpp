#include <expo-modules-v2/views/ViewPropsNatives.h>

#include <vector>

#include <kolibri/binary/BinaryBuffer.h>

#include <expo-modules-v2/jsi/JavaScriptValue.h>
#include <expo-modules-v2/utils/Throw.h>
#include <expo-modules-v2/views/EncodedViewProps.h>
#include <expo-modules-v2/views/ViewPropsCodec.h>

namespace expo::modules::v2::views {
  namespace {
    /**
     * Writes the props of [handle] that changed since [mountedRevisions] into the calling thread's
     * bridge buffer. Returns the payload's length, or -1 when the handle no longer resolves.
     */
    jint readChanges(JNIEnv* env, jlong handle, jlongArray mountedRevisions) {
      const EncodedViewProps::Shared props = EncodedViewProps::lookup(handle);
      if (!props) {
        return -1;
      }

      std::vector<int64_t> mounted;
      if (mountedRevisions != nullptr) {
        mounted.resize(static_cast<size_t>(env->GetArrayLength(mountedRevisions)));
        env->GetLongArrayRegion(
          mountedRevisions,
          0,
          static_cast<jsize>(mounted.size()),
          reinterpret_cast<jlong*>(mounted.data())
        );
      }

      const kolibri::binary::BinaryBuffer::Claim claim;
      if (!claim) {
        throwRuntimeError({"The binary bridge buffer is already in use"});
      }
      kolibri::binary::BinaryBuffer& out = claim.buffer();
      out.clear();
      props->writeChanges(out, mounted.empty() ? nullptr : mounted.data(), mounted.size());
      return static_cast<jint>(out.size());
    }
  } // namespace

  void ViewPropsNatives::registerNatives(JNIEnv* env) {
    JavaClass::registerNatives(env)
      .method(
        "nativeCreateCodec",
        [](JNIEnv* env, jobject decoder) -> jlong {
          return reinterpret_cast<jlong>(new ViewPropsCodec(env, decoder));
        }
      )
      .method(
        "nativeDestroyCodec",
        [](jlong pointer) {
          delete reinterpret_cast<ViewPropsCodec*>(pointer);
        }
      )
      .method(
        "nativeReadChanges",
        [](JNIEnv* env, jlong handle, jlongArray mountedRevisions) -> jint {
          return readChanges(env, handle, mountedRevisions);
        }
      )
      // Test-only: an "owner" stands in for the Fabric props that own an `EncodedViewProps`.
      .method(
        "nativeEncodeForTesting",
        [](jlong codecPointer, jlong valuePointer, jlong previousOwner) -> jlong {
          const auto* codec = reinterpret_cast<const ViewPropsCodec*>(codecPointer);
          const auto* value = reinterpret_cast<const jsi::JavaScriptValue*>(valuePointer);
          const auto* previous = reinterpret_cast<const EncodedViewProps::Shared*>(previousOwner);
          facebook::jsi::Runtime& rt = value->runtime();
          if (!value->value().isObject()) {
            return 0;
          }
          EncodedViewProps::Shared encoded = codec->encodeObject(
            rt,
            value->value().getObject(rt),
            previous != nullptr ? *previous : nullptr
          );
          if (!encoded) {
            return 0;
          }
          return reinterpret_cast<jlong>(new EncodedViewProps::Shared(std::move(encoded)));
        }
      )
      .method(
        "nativeHandleOf",
        [](jlong owner) -> jlong {
          return (*reinterpret_cast<const EncodedViewProps::Shared*>(owner))->handle();
        }
      )
      .method(
        "nativeReleaseOwner",
        [](jlong owner) {
          delete reinterpret_cast<const EncodedViewProps::Shared*>(owner);
        }
      )
      .commit();
  }
} // namespace expo::modules::v2::views
