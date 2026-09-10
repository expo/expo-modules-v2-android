#include <expo-modules-v2/sharedobjects/SharedObjectClassObject.h>

#include <expo-modules-v2/objects/RuntimeObjects.h>

namespace expo::modules::v2::sharedobjects {
  namespace {
    void defineHidden(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& target,
      const char* name,
      const facebook::jsi::Object& value
    ) {
      facebook::jsi::Object descriptor(rt);
      descriptor.setProperty(rt, "value", value);
      descriptor.setProperty(rt, "enumerable", false);
      descriptor.setProperty(rt, "configurable", false);

      rt.global()
        .getPropertyAsObject(rt, "Object")
        .getPropertyAsFunction(rt, "defineProperty")
        .call(rt, target, name, descriptor);
    }
  } // namespace

  facebook::jsi::Function createClassConstructor(
    facebook::jsi::Runtime& rt,
    const descriptor::SharedClassSpec& spec
  ) {
    // Captures the spec by pointer: it lives in the module object's node, which owns this function.
    facebook::jsi::Function classObject = facebook::jsi::Function::createFromHostFunction(
      rt,
      facebook::jsi::PropNameID::forUtf8(rt, spec.constructor.name),
      spec.constructor.argTypes.size(),
      [constructor = &spec.constructor](
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value&,
      const facebook::jsi::Value* args,
      size_t count
    ) -> facebook::jsi::Value {
        return constructor->invoke(rt, args, count);
      }
    );

    objects::RuntimeObjects* table = objects::RuntimeObjects::find(rt);
    if (table != nullptr) {
      const facebook::jsi::Object& prototype =
        table->prototypeFor(rt, spec.classId, /* installMembers */ false);
      defineHidden(rt, classObject, "prototype", prototype);
      defineHidden(rt, prototype, "constructor", classObject);
    }

    return classObject;
  }
} // namespace expo::modules::v2::sharedobjects
