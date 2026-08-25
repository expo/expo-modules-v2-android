#include <expo-modules-v2/jsi/ModulesHostObject.h>

#include <utility>

#include <expo-jsi/ChainedNativeState.h>
#include <kolibri/binary/BinaryBuffer.h>
#include <kolibri/env.h>

#include <expo-modules-v2/decoders/ModuleDescriptorDecoder.h>
#include <expo-modules-v2/modules/ModuleNativeState.h>
#include <expo-modules-v2/binders/PropertyBinder.h>

namespace expo::modules::v2::jsi {
  namespace {
    // JSI has no direct property-descriptor API. Define a real JavaScript accessor on the plain
    // module object so reads invoke Kotlin every time, `var` writes reach its setter, and `val`
    // stays read-only while preserving the module object's NativeState.
    void defineHostProperty(
      facebook::jsi::Runtime& rt,
      facebook::jsi::Object& moduleObject,
      const PropertyBinder& binder
    ) {
      facebook::jsi::Object descriptor(rt);
      descriptor.setProperty(rt, "get", binder.createGetter(rt));
      if (binder.hasSetter()) {
        descriptor.setProperty(rt, "set", binder.createSetter(rt));
      }
      descriptor.setProperty(rt, "enumerable", true);
      descriptor.setProperty(rt, "configurable", false);

      rt.global()
        .getPropertyAsObject(rt, "Object")
        .getPropertyAsFunction(rt, "defineProperty")
        .call(rt, moduleObject, binder.name(), descriptor);
    }
  } // namespace

  ModulesHostObject::ModulesHostObject(
    JNIEnv* env,
    jobject registry
  )
    : registry_(kolibri::GlobalRef<JModuleRegistry>::make(env, registry)) {
  }

  ModulesHostObject::~ModulesHostObject() {
    // Hermes may release a host object on an otherwise JNI-detached GC thread. Attach while that
    // thread is still alive so the registry and resolved module states can release their global
    // refs with this object instead of deferring their deletion to JavaScriptRuntime.
    if (registry_) {
      kolibri::getEnv();
      registry_.reset();
    }
  }

  facebook::jsi::Value ModulesHostObject::get(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::PropNameID& name
  ) {
    const std::string moduleName = name.utf8(rt);
    if (const auto cached = materialized_.find(moduleName); cached != materialized_.end()) {
      return facebook::jsi::Value(rt, cached->second);
    }

    try {
      JNIEnv* env = kolibri::getEnv();
      auto module = registry_->encodeModule(env, moduleName);
      if (!module.has_value()) {
        // TODO(@lukmccall): consider throwing
        return facebook::jsi::Value::undefined();
      }

      auto& [instance, desc] = module.value();

      auto state = std::make_shared<ModuleNativeState>(
        kolibri::GlobalRef<>::make(env, instance.get()),
        std::move(desc.functions),
        std::move(desc.properties)
      );

      facebook::jsi::Object moduleObject(rt);
      for (const FunctionBinder& binder: state->functionBinders()) {
        moduleObject.setProperty(
          rt,
          facebook::jsi::PropNameID::forUtf8(rt, binder.name()),
          binder.createFunction(rt)
        );
      }

      for (const PropertyBinder& binder: state->propertyBinders()) {
        defineHostProperty(rt, moduleObject, binder);
      }

      expo::jsi::ChainedNativeState::attach(rt, moduleObject, state);

      // Cache only a fully built module object. Its attached native state owns the module instance
      // and unresolved metadata; successful first-call lookups populate that same state in place.
      // A descriptor/JSI failure during materialization still retries from scratch.
      return facebook::jsi::Value(
        rt,
        materialized_.emplace(moduleName, std::move(moduleObject)).first->second
      );
    } catch (facebook::jsi::JSError&) {
      throw;
    } catch (const std::exception& error) {
      throw facebook::jsi::JSError(
        rt,
        "Failed to materialize module '" + moduleName + "': " + error.what()
      );
    }
  }

  void ModulesHostObject::set(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::PropNameID& name,
    const facebook::jsi::Value&
  ) {
    throw facebook::jsi::JSError(
      rt,
      "expo.modules is read-only (attempted to set '" + name.utf8(rt) + "')"
    );
  }

  std::vector<facebook::jsi::PropNameID> ModulesHostObject::getPropertyNames(
    facebook::jsi::Runtime& rt
  ) {
    JNIEnv* env = kolibri::getEnv();
    std::vector<std::string> names = registry_->encodeModuleNames(env);

    std::vector<facebook::jsi::PropNameID> output;
    output.reserve(names.size());

    std::ranges::transform(
      names,
      std::back_inserter(output),
      [&](const std::string& name) {
        return facebook::jsi::PropNameID::forUtf8(rt, name);
      }
    );

    return output;
  }
} // namespace expo::modules::v2::jsi
