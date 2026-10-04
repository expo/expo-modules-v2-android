#include <expo-modules-v2/jsi/ModulesHostObject.h>

#include <string>
#include <unordered_set>
#include <utility>

#include <expo-jsi/ChainedNativeState.h>
#include <kolibri/binary/BinaryBuffer.h>
#include <kolibri/env.h>

#include <expo-modules-v2/decoders/ModuleDescriptorDecoder.h>
#include <expo-modules-v2/modules/ModuleNativeState.h>
#include <expo-modules-v2/modules/ModuleState.h>
#include <expo-modules-v2/objects/ObjectId.h>
#include <expo-modules-v2/objects/ObjectRegistry.h>
#include <expo-modules-v2/objects/RuntimeObjects.h>
#include <expo-modules-v2/sharedobjects/SharedObjectClassObject.h>

namespace expo::modules::v2::jsi {
  namespace {
    /**
     * A host function calling [spec] on whatever [receiver] holds at call time. Both are captured by
     * pointer: the spec lives in the module object's node and the receiver ref in the state that
     * node owns, so neither dies before a function reachable from the module object.
     */
    facebook::jsi::Function hostFunction(
      facebook::jsi::Runtime& rt,
      const descriptor::HostFunctionSpec& spec,
      const kolibri::GlobalRef<>& receiver
    ) {
      return facebook::jsi::Function::createFromHostFunction(
        rt,
        facebook::jsi::PropNameID::forUtf8(rt, spec.name),
        spec.argTypes.size(),
        [spec = &spec, receiver = &receiver](
        facebook::jsi::Runtime& runtime,
        const facebook::jsi::Value&,
        const facebook::jsi::Value* args,
        const size_t count
      ) -> facebook::jsi::Value {
          return spec->invoke(runtime, receiver->get(), args, count);
        }
      );
    }

    /** `Object.defineProperty(object, name, descriptor)`: JSI has no property-descriptor API. */
    void defineProperty(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& object,
      const std::string& name,
      const facebook::jsi::Object& descriptor
    ) {
      rt.global()
        .getPropertyAsObject(rt, "Object")
        .getPropertyAsFunction(rt, "defineProperty")
        .call(rt, object, name, descriptor);
    }

    facebook::jsi::Function constantGetter(
      facebook::jsi::Runtime& rt,
      const descriptor::HostPropertySpec& property,
      const kolibri::GlobalRef<>& receiver
    ) {
      return facebook::jsi::Function::createFromHostFunction(
        rt,
        facebook::jsi::PropNameID::forUtf8(rt, property.name),
        0,
        [property = &property, receiver = &receiver](
        facebook::jsi::Runtime& runtime,
        const facebook::jsi::Value& thisValue,
        const facebook::jsi::Value*,
        size_t) -> facebook::jsi::Value {
          facebook::jsi::Value value = property->get(runtime, receiver->get());

          if (thisValue.isObject()) {
            const facebook::jsi::Object descriptor(runtime);
            descriptor.setProperty(runtime, "value", facebook::jsi::Value(runtime, value));
            descriptor.setProperty(runtime, "writable", false);
            descriptor.setProperty(runtime, "enumerable", true);
            descriptor.setProperty(runtime, "configurable", false);
            try {
              defineProperty(runtime, thisValue.getObject(runtime), property->name, descriptor);
            } catch (const facebook::jsi::JSError&) {
              // The getter was called on an object that cannot take the property, such as a
              // frozen one. The value is still right; only the caching is lost.
            }
          }
          return value;
        }
      );
    }

    void defineHostProperty(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& moduleObject,
      const descriptor::HostPropertySpec& property,
      const kolibri::GlobalRef<>& receiver
    ) {
      facebook::jsi::Object descriptor(rt);
      if (property.isConstant) {
        descriptor.setProperty(rt, "get", constantGetter(rt, property, receiver));
        descriptor.setProperty(rt, "configurable", true);
      } else {
        descriptor.setProperty(rt, "get", hostFunction(rt, property.getter, receiver));
        if (property.hasSetter()) {
          descriptor.setProperty(rt, "set", hostFunction(rt, *property.setter, receiver));
        }
        descriptor.setProperty(rt, "configurable", false);
      }
      descriptor.setProperty(rt, "enumerable", true);

      defineProperty(rt, moduleObject, property.name, descriptor);
    }
  } // namespace

  ModulesHostObject::ModulesHostObject(
    JNIEnv* env,
    jobject registry,
    std::optional<facebook::jsi::Object> hostModules
  )
    : registry_(kolibri::GlobalRef<JModuleRegistry>::make(env, registry)),
      hostModules_(std::move(hostModules)) {
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

    // Not cached here: the host caches its own modules, and a miss must stay a miss for the
    // registry below.
    if (hostModules_.has_value()) {
      facebook::jsi::Value hostModule = hostModules_->getProperty(rt, name);
      if (!hostModule.isUndefined()) {
        return hostModule;
      }
    }

    try {
      JNIEnv* env = kolibri::getEnv();

      // One ModuleState per instance, shared by every module object standing for it; one
      // ModuleNativeState per module object, since each registration has its own export table.
      // The state comes first because the decoded specs bind to the class it holds.
      std::shared_ptr<ModuleState> shared;
      std::optional<descriptor::ModuleDescriptorPayload> desc = registry_->encodeModule(
        env,
        moduleName,
        [&shared](JNIEnv* env, const jobject instance) -> jclass {
          const objects::ObjectId::Value objectId = objects::ObjectId::of(env, instance);
          shared = objects::ObjectRegistry::find<ModuleState>(objectId);
          if (shared == nullptr) {
            shared = objects::ObjectRegistry::adopt(
              std::make_shared<ModuleState>(env, objectId, kolibri::GlobalRef<>::make(env, instance))
            );
          }
          return shared->javaClass();
        }
      );

      if (!desc.has_value()) {
        // TODO(@lukmccall): consider throwing
        return facebook::jsi::Value::undefined();
      }

      const objects::ObjectId::Value objectId = shared->objectId();
      const auto state = std::make_shared<ModuleNativeState>(
        std::move(shared),
        std::move(desc->functions),
        std::move(desc->properties),
        std::move(desc->sharedClasses),
        std::move(desc->events)
      );

      objects::RuntimeObjects* table = objects::RuntimeObjects::find(rt);

      facebook::jsi::Object moduleObject(rt);
      // Every module is an event emitter, whether or not it declares events: subscribing to an
      // unknown name then fails with a message naming the declared ones.
      if (table != nullptr) {
        moduleObject.setPrototype(rt, facebook::jsi::Value(rt, table->eventEmitterPrototype(rt)));
      }

      const kolibri::GlobalRef<>& receiver = state->moduleState().instanceRef();
      for (const descriptor::HostFunctionSpec& function: state->functions()) {
        moduleObject.setProperty(
          rt,
          facebook::jsi::PropNameID::forUtf8(rt, function.name),
          hostFunction(rt, function, receiver)
        );
      }

      for (const descriptor::HostPropertySpec& property: state->properties()) {
        defineHostProperty(rt, moduleObject, property, receiver);
      }

      for (const descriptor::SharedClassSpec& sharedClass: state->sharedClasses()) {
        moduleObject.setProperty(
          rt,
          facebook::jsi::PropNameID::forUtf8(rt, sharedClass.jsName),
          sharedobjects::createClassConstructor(rt, sharedClass)
        );
      }

      expo::jsi::ChainedNativeState::attach(rt, moduleObject, state);

      // `materialized_` below holds the strong reference; the weak entry lets a Kotlin instance be
      // mapped back to this object.
      if (table != nullptr) {
        table->store(rt, objectId, moduleObject);
      }

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

    // A name both sides have is listed once, the way `get` resolves it once.
    std::unordered_set<std::string> listed;
    const auto list = [&](std::string name) {
      if (listed.insert(name).second) {
        output.push_back(facebook::jsi::PropNameID::forUtf8(rt, name));
      }
    };

    if (hostModules_.has_value()) {
      const facebook::jsi::Array hostNames = hostModules_->getPropertyNames(rt);
      const size_t count = hostNames.size(rt);
      for (size_t i = 0; i < count; ++i) {
        list(hostNames.getValueAtIndex(rt, i).toString(rt).utf8(rt));
      }
    }

    for (std::string& name: names) {
      list(std::move(name));
    }

    return output;
  }
} // namespace expo::modules::v2::jsi
