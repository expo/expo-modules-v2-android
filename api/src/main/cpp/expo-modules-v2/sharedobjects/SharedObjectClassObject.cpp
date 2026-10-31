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

    /**
     * `(construct, name, length) => class`.
     *
     * The class can't be the host function itself: Hermes calls a host function with an undefined
     * `this` and no `new.target`, so `super()` in a JavaScript subclass would return an instance with
     * the native class's prototype, without the subclass's members. A JavaScript function can read
     * `new.target` and give the instance the subclass's prototype.
     */
    facebook::jsi::Function makeClassFactory(facebook::jsi::Runtime& rt) {
      static constexpr auto kSource =
        "(function (construct, name, length) {"
        "  const Class = function () {"
        "    const instance = construct.apply(undefined, arguments);"
        "    if (new.target !== undefined && new.target !== Class) {"
        "      Object.setPrototypeOf(instance, new.target.prototype);"
        "    }"
        "    return instance;"
        "  };"
        "  Object.defineProperty(Class, 'name', { value: name });"
        "  Object.defineProperty(Class, 'length', { value: length });"
        "  return Class;"
        "})";

      return rt.evaluateJavaScript(
        std::make_shared<facebook::jsi::StringBuffer>(kSource),
        "expo-modules-v2/shared-class.js"
      ).getObject(rt).getFunction(rt);
    }
  } // namespace

  facebook::jsi::Function createClassConstructor(
    facebook::jsi::Runtime& rt,
    const descriptor::SharedClassSpec& spec
  ) {
    // Captures the spec by pointer: it lives in the module object's node, which owns this function.
    facebook::jsi::Function construct = facebook::jsi::Function::createFromHostFunction(
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
    facebook::jsi::Function classObject = makeClassFactory(rt)
      .call(
        rt,
        construct,
        facebook::jsi::String::createFromUtf8(rt, spec.constructor.name),
        static_cast<double>(spec.constructor.argTypes.size())
      )
      .getObject(rt)
      .getFunction(rt);

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
