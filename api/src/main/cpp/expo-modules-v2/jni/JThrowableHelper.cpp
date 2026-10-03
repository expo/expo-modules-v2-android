#include <expo-modules-v2/jni/JThrowableHelper.h>

namespace expo::modules::v2 {
  std::optional<ThrowableDetails> JThrowableHelper::takePending(JNIEnv* env) {
    if (!env->ExceptionCheck()) [[likely]] {
      return std::nullopt;
    }

    const kolibri::Ref<> throwable = kolibri::Ref<>::adopt(env, env->ExceptionOccurred());
    // Nothing can call into Java while an exception is pending, `describe` included.
    env->ExceptionClear();

    const auto fields = describe_(env, throwable.get());
    return ThrowableDetails{
      .code = fields->getUnsafeElement(env, 0)->toStdString(env),
      .message = fields->getUnsafeElement(env, 1)->toStdString(env),
      .stack = fields->getUnsafeElement(env, 2)->toStdString(env),
    };
  }
}
