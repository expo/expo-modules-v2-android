#include <expo-modules-v2/binders/FunctionBinder.h>

#include <expo-modules-v2/JniMethodInvoker.h>
#include <expo-modules-v2/modules/ModuleNativeState.h>

#include <utility>

#include <kolibri/env.h>
#include <kolibri/Ref.h>

namespace expo::modules::v2 {
  using descriptor::HostFunctionSpec;

  FunctionBinder::FunctionBinder(
    HostFunctionSpec spec,
    std::shared_ptr<kolibri::GlobalRef<>> instance
  ) : spec_(std::make_shared<HostFunctionSpec>(std::move(spec))),
      instance_(std::move(instance)) {
  }

  const std::string& FunctionBinder::name() const {
    return spec_->name;
  }

  const HostFunctionSpec& FunctionBinder::resolve() const {
    if (spec_->method == nullptr) {
      JNIEnv* env = kolibri::getEnv();
      const kolibri::Ref<> receiverClass = kolibri::Ref<>::adopt(
        env,
        env->GetObjectClass(instance_->get())
      );
      spec_->resolveFunction(
        env,
        reinterpret_cast<jclass>(receiverClass.get())
      );
      declaringClass_ = kolibri::GlobalRef<>::make(env, receiverClass.get());
      spec_->declaringClass = reinterpret_cast<jclass>(declaringClass_.get());
    }
    return *spec_;
  }

  facebook::jsi::Function FunctionBinder::createFunction(
    facebook::jsi::Runtime& rt
  ) const {
    const FunctionInvoker invoke = selectFunctionInvoker(
      spec_->returnType,
      spec_->hasBufferedArgs(),
      spec_->needsLocalFrame(),
      spec_->async
    );

    return facebook::jsi::Function::createFromHostFunction(
      rt,
      facebook::jsi::PropNameID::forUtf8(rt, spec_->name),
      spec_->argTypes.size(),
      [binder = this, invoke](
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value&,
      const facebook::jsi::Value* args,
      size_t count
    ) -> facebook::jsi::Value {
        try {
          const HostFunctionSpec& spec = binder->resolve();
          JNIEnv* env = kolibri::getEnv();
          return invoke(
            rt,
            env,
            spec,
            binder->instance_->get(),
            args,
            count
          );
        } catch (const facebook::jsi::JSError&) {
          throw;
        } catch (const std::exception& e) {
          throw facebook::jsi::JSError(rt, std::string("native call failed: ") + e.what());
        }
      }
    );
  }
} // namespace expo::modules::v2
