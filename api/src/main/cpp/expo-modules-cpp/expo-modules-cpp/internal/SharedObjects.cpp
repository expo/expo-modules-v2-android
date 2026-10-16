#include <expo-modules-cpp/internal/SharedObjects.h>

#include <string>
#include <utility>

#include <expo-modules-cpp/Context.h>
#include <expo-modules-cpp/SharedObject.h>
#include <expo-modules-cpp/internal/Invoke.h>
#include <expo-modules-cpp/internal/Members.h>
#include <expo-modules-cpp/internal/ObjectNode.h>
#include <expo-modules-cpp/internal/RuntimeData.h>

namespace expo::modules::v2::cpp::detail {
  namespace {
    /** The shared object a prototype member was called on, released or not. */
    SharedObject& sharedObjectOf(js::Runtime& rt, const js::Value& thisValue, const std::string& member) {
      if (thisValue.isObject()) {
        const js::Object self = thisValue.getObject(rt);
        if (ObjectNode* const node = ObjectNode::borrow(rt, self); node != nullptr && !node->isModule()) {
          return static_cast<SharedObject&>(node->object());
        }
      }
      throwJSError(rt, "'" + member + "' called on something that is not a shared object");
    }

    // The two members every prototype gets are plain functions, not lambdas: a `std::function` of a
    // function pointer shares one instantiation, where each lambda adds its own.
    js::Value releaseMember(js::Runtime& rt, const js::Value& thisValue, const js::Value*, size_t) {
      sharedObjectOf(rt, thisValue, "release").release();
      return js::Value::undefined();
    }

    js::Value objectIdMember(js::Runtime& rt, const js::Value& thisValue, const js::Value*, size_t) {
      return js::Value(static_cast<double>(sharedObjectOf(rt, thisValue, "__expoSharedObjectId").objectId()));
    }
  } // namespace

  void installPrototypeMembers(RuntimeData& data, const js::Object& prototype, const ClassSpec& spec) {
    js::Runtime& rt = data.runtime();

    defineData(
      data,
      prototype,
      "release",
      js::Value(js::Function::createFromHostFunction(rt, js::PropNameID::forAscii(rt, "release"), 0, &releaseMember)),
      PropertyFlags{}
    );

    defineAccessor(
      data,
      prototype,
      "__expoSharedObjectId",
      js::Function::createFromHostFunction(rt, js::PropNameID::forAscii(rt, "__expoSharedObjectId"), 0, &objectIdMember),
      nullptr,
      PropertyFlags{}
    );

    for (const FunctionSpec& function: spec.functions) {
      defineData(data, prototype, function.name, js::Value(makeHostFunction(rt, function)), PropertyFlags{});
    }

    // A constant is the instance's own hidden value once it was read: it stays out of `Object.keys`
    // and `JSON.stringify`, so an instance looks the same before and after its first read.
    defineProperties(data, prototype, spec.properties, spec.constants, nullptr, /* hiddenConstants */ true);
  }

  js::Function createClassConstructor(RuntimeData& data, const ClassBinding& binding) {
    js::Runtime& rt = data.runtime();
    const ClassSpec& spec = *binding.spec;
    const FunctionSpec* const constructor = &*spec.constructor;

    js::Function classObject = js::Function::createFromHostFunction(
      rt,
      js::PropNameID::forUtf8(rt, binding.jsName),
      constructor->arity,
      [constructor](js::Runtime& runtime, const js::Value& thisValue, const js::Value* args, const size_t count) -> js::Value {
        return callFunction(*constructor, runtime, thisValue, args, count);
      }
    );

    const js::Object& prototype = data.classPrototype(spec, /* installMembers */ false);
    defineData(data, classObject, "prototype", js::Value(rt, prototype), PropertyFlags{});
    // Writable and configurable, as `constructor` is on a function's own prototype: a class that two
    // modules export is constructed by whichever was built last.
    defineData(data, prototype, "constructor", js::Value(rt, classObject), PropertyFlags{.writable = true, .configurable = true});
    return classObject;
  }

  std::shared_ptr<SharedObject> sharedObjectFromJS(js::Runtime& rt, const js::Value& value, const ClassSpec& declared) {
    if (!value.isObject()) [[unlikely]] {
      throwConversion("expected " + declared.name + ", got " + describe(rt, value));
    }

    const js::Object object = value.getObject(rt);
    ObjectNode* const node = ObjectNode::borrow(rt, object);
    if (node == nullptr || node->isModule()) [[unlikely]] {
      throwConversion("expected " + declared.name + ", got an object that is not a shared object");
    }

    auto& shared = static_cast<SharedObject&>(node->object());
    if (shared.isReleased()) [[unlikely]] {
      throwConversion("expected a " + declared.name + " that is not released, got a released " + node->spec()->name);
    }
    if (!node->spec()->isA(&declared)) [[unlikely]] {
      throwConversion("expected " + declared.name + ", got " + node->spec()->name);
    }
    return std::static_pointer_cast<SharedObject>(node->shared());
  }

  js::Value sharedObjectToJS(js::Runtime& rt, const std::shared_ptr<SharedObject>& object, const ClassSpec& declared) {
    return RuntimeData::require(rt).facadeFor(object, declared);
  }
} // namespace expo::modules::v2::cpp::detail

namespace expo::modules::v2::cpp::detail {
  js::Value RuntimeData::facadeFor(const std::shared_ptr<SharedObject>& object, const ClassSpec& declared) {
    if (js::Value existing = lookup(object->objectId()); !existing.isUndefined()) {
      return existing;
    }

    // The class the object is, as far as JavaScript is concerned: the one it first reached
    // JavaScript as, and otherwise the one it is being converted as.
    const ClassSpec* spec = object->spec_.load(std::memory_order_acquire);
    if (spec == nullptr) {
      const ClassSpec* expected = nullptr;
      object->spec_.compare_exchange_strong(expected, &declared, std::memory_order_acq_rel);
      spec = object->spec_.load(std::memory_order_acquire);
    }

    object->bindContext(context_);

    js::Runtime& rt = *runtime_;
    js::Object facade(rt);
    facade.setPrototype(rt, js::Value(rt, classPrototype(*spec, /* installMembers */ true)));
    ::expo::jsi::ChainedNativeState::attach(rt, facade, std::make_shared<ObjectNode>(object, spec));
    store(object->objectId(), facade);

    if (const size_t pressure = object->externalMemoryPressure(); pressure > 0) {
      facade.setExternalMemoryPressure(rt, pressure);
    }
    return js::Value(std::move(facade));
  }

  const js::Object& RuntimeData::classPrototype(const ClassSpec& spec, const bool installMembers) {
    auto found = prototypes_.find(&spec);
    if (found == prototypes_.end()) {
      js::Object object(*runtime_);
      // A class extends its parent class, and the root of the chain is the emitter, as
      // `SharedObject extends EventEmitter` does in JavaScript.
      const js::Object& parent = spec.parent != nullptr ? classPrototype(*spec.parent, false) : emitterPrototype();
      object.setPrototype(*runtime_, js::Value(*runtime_, parent));
      found = prototypes_.emplace(&spec, Prototype{.object = std::move(object)}).first;
    }

    Prototype& prototype = found->second;
    if (installMembers && !prototype.installed) {
      if (spec.parent != nullptr) {
        (void) classPrototype(*spec.parent, true);
      }
      installPrototypeMembers(*this, prototype.object, spec);
      prototype.installed = true;
    }
    return prototype.object;
  }
} // namespace expo::modules::v2::cpp::detail
