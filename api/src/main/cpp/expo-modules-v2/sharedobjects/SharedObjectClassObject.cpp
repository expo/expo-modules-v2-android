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
    descriptor::SharedClassSpec& spec
  ) {
    facebook::jsi::Function classObject = spec.binder().createFunction(rt);

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
