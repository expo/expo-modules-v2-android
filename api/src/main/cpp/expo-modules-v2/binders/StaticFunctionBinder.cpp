#include <expo-modules-v2/binders/StaticFunctionBinder.h>

#include <utility>

#include <kolibri/env.h>

namespace expo::modules::v2 {
  using descriptor::StaticFunctionSpec;

  StaticFunctionBinder::StaticFunctionBinder(
    std::shared_ptr<StaticFunctionSpec> spec,
    const jclass declaringClass
  ) : spec_(std::move(spec)),
      declaringClass_(declaringClass),
      invoker_(
        selectStaticObjectInvoker(
          spec_->hasBufferedArgs(),
          spec_->needsLocalFrame()
        )
      ) {
    spec_->declaringClass = declaringClass;
  }

  const std::string& StaticFunctionBinder::name() const {
    return spec_->name;
  }

  const StaticFunctionSpec& StaticFunctionBinder::resolve() const {
    if (spec_->method == nullptr) {
      spec_->resolveFunction(kolibri::getEnv(), declaringClass_);
    }
    return *spec_;
  }

  facebook::jsi::Value StaticFunctionBinder::invoke(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Value* args,
    const size_t count
  ) const {
    try {
      const StaticFunctionSpec& spec = resolve();
      return invoker_(rt, kolibri::getEnv(), spec, /* receiver */ nullptr, args, count);
    } catch (const facebook::jsi::JSError&) {
      throw;
    } catch (const std::exception& e) {
      throw facebook::jsi::JSError(rt, std::string("native call failed: ") + e.what());
    }
  }

  facebook::jsi::Function StaticFunctionBinder::createFunction(
    facebook::jsi::Runtime& rt
  ) const {
    return facebook::jsi::Function::createFromHostFunction(
      rt,
      facebook::jsi::PropNameID::forUtf8(rt, spec_->name),
      spec_->argTypes.size(),
      [binder = this](
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value&,
      const facebook::jsi::Value* args,
      size_t count
    ) -> facebook::jsi::Value {
        return binder->invoke(rt, args, count);
      }
    );
  }
} // namespace expo::modules::v2
