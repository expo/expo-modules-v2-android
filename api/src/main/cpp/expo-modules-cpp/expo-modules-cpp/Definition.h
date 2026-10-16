#pragma once

#include <memory>
#include <string>
#include <string_view>
#include <type_traits>
#include <utility>

#include <expo-modules-cpp/SharedObject.h>
#include <expo-modules-cpp/internal/Bindings.h>
#include <expo-modules-cpp/internal/Invoker.h>
#include <expo-modules-cpp/internal/Specs.h>

namespace expo::modules::v2::cpp {
  /**
   * What a module exports to JavaScript. A module describes itself in `Module::define`:
   *
   * ```cpp
   * void define(cpp::ModuleDefinition& def) override {
   *   def.constant("PI", 3.14159);
   *   def.function("add", [](int a, int b) { return a + b; });
   *   def.function("scale", this, &MathModule::scale);
   *   def.property("factor", [this] { return factor_; }, [this](double value) { factor_ = value; });
   *   def.sharedClass<Counter>();
   * }
   * ```
   *
   * An export is a C++ callable, and the library reads its signature: each parameter is converted
   * from the JavaScript argument (see `cpp::Converter`), the result is converted back, and an
   * exception becomes a JavaScript `Error`. A parameter that is a `js::Runtime&` receives the
   * runtime and takes no argument; trailing `std::optional` parameters can be left out of a call.
   * A function that takes a `cpp::Promise` or returns a `cpp::Task` returns a promise.
   *
   * A name must be unique within the module and may not be one of the event emitter's.
   */
  class ModuleDefinition {
  public:
    ModuleDefinition(detail::ModuleSpec& spec, std::string moduleName)
      : spec_(spec), names_(std::move(moduleName), /* isClass */ false) {
    }

    /** Exports [callable] as a function. */
    template<detail::Callable F>
    void function(std::string name, F&& callable) {
      names_.claim(name);
      spec_.functions.push_back(detail::makeFunction(std::move(name), std::forward<F>(callable)));
    }

    /** Exports the member [method] of [self], usually the module itself. */
    template<detail::ObjectPointer C, detail::MemberFunctionPointer M>
    void function(std::string name, C self, M method) {
      function(std::move(name), detail::BoundMethod<std::decay_t<M>>{self, method});
    }

    /** A read-only property: [getter] runs at every read. */
    template<detail::Callable G>
    void property(std::string name, G&& getter) {
      names_.claim(name);
      spec_.properties.push_back(detail::PropertySpec{
        .name = name,
        .getter = detail::makeFunction(name, std::forward<G>(getter)),
        .setter = std::nullopt,
      });
    }

    /** A read-write property. */
    template<detail::Callable G, detail::Callable S>
    void property(std::string name, G&& getter, S&& setter) {
      names_.claim(name);
      spec_.properties.push_back(detail::PropertySpec{
        .name = name,
        .getter = detail::makeFunction(name, std::forward<G>(getter)),
        .setter = detail::makeFunction(name, std::forward<S>(setter)),
      });
    }

    /** A property backed by the data member [member] of [self]; read-only when the member is const. */
    template<detail::ObjectPointer C, detail::MemberObjectPointer M>
    void property(std::string name, C self, M member) {
      using Member = detail::DataMember<std::decay_t<M>>;
      using Value = std::remove_const_t<typename Member::Value>;
      using Owner = typename Member::Class;

      Owner* const owner = self;
      auto reader = detail::BoundMemberReader<Owner, typename Member::Value>{owner, member};
      if constexpr (std::is_const_v<typename Member::Value>) {
        property(std::move(name), std::move(reader));
      } else {
        property(std::move(name), std::move(reader), detail::BoundMemberWriter<Owner, Value>{owner, member});
      }
    }

    /** A read-only property read with the member function [getter] of [self]. */
    template<detail::ObjectPointer C, detail::MemberFunctionPointer G>
    void property(std::string name, C self, G getter) {
      property(std::move(name), detail::BoundMethod<std::decay_t<G>>{self, getter});
    }

    /** A read-write property read and written with member functions of [self]. */
    template<detail::ObjectPointer C, detail::MemberFunctionPointer G, detail::MemberFunctionPointer S>
    void property(std::string name, C self, G getter, S setter) {
      property(
        std::move(name),
        detail::BoundMethod<std::decay_t<G>>{self, getter},
        detail::BoundMethod<std::decay_t<S>>{self, setter}
      );
    }

    /**
     * A constant: JavaScript reads it and keeps it as a plain read-only value. A value is read when
     * the module object is built. A callable is a getter that runs at the first read, once - and again
     * at the next read if it threw.
     */
    template<typename T>
    void constant(std::string name, T&& valueOrGetter) {
      names_.claim(name);
      if constexpr (detail::Callable<T>) {
        spec_.constants.push_back(detail::ConstantSpec{
          .name = name,
          .getter = detail::makeFunction(name, std::forward<T>(valueOrGetter)),
          .lazy = true,
        });
      } else {
        using Stored = detail::ConstantStorage<T>;
        spec_.constants.push_back(detail::ConstantSpec{
          .name = name,
          .getter = detail::makeFunction(name, detail::ConstantValue<Stored>{Stored(std::forward<T>(valueOrGetter))}),
          .lazy = false,
        });
      }
    }

    /** A constant read with the member function [getter] of [self], at the first read. */
    template<detail::ObjectPointer C, detail::MemberFunctionPointer G>
    void constant(std::string name, C self, G getter) {
      constant(std::move(name), detail::BoundMethod<std::decay_t<G>>{self, getter});
    }

    /**
     * Exports the shared object class [T] as a constructor JavaScript calls with `new`, under
     * [jsName] - the class's own name by default. A class without a constructor is not exposed:
     * JavaScript only receives its instances.
     */
    template<detail::SharedObjectType T>
    void sharedClass(std::string jsName = {}) {
      const detail::ClassSpec& spec = detail::classSpecOf<T>();
      if (!spec.constructor.has_value()) {
        return;
      }
      if (jsName.empty()) {
        jsName = spec.name;
      }
      names_.claim(jsName);
      spec_.classes.push_back(detail::ClassBinding{.jsName = std::move(jsName), .spec = &spec});
    }

  private:
    detail::ModuleSpec& spec_;
    detail::ExportNames names_;
  };

  /**
   * What a shared object class exports to JavaScript, described in the class's static `define`.
   * Members are written against the class, and the object a member was called on is the callable's
   * first parameter when that parameter is the class (`C&`, `const C&`, `C*` or
   * `std::shared_ptr<C>`) - or the object of a member function pointer.
   *
   * ```cpp
   * static void define(cpp::ClassDefinition<Counter>& cls) {
   *   cls.constructor<int>();
   *   cls.function("increment", &Counter::increment);
   *   cls.function("reset", [](Counter& self) { self.value_ = 0; });
   *   cls.property("value", &Counter::value_);
   * }
   * ```
   */
  template<typename T>
  class ClassDefinition {
  public:
    explicit ClassDefinition(detail::ClassSpec& spec) : spec_(spec), names_(spec.name, /* isClass */ true) {
      if constexpr (std::is_base_of_v<detail::SharedRefBase, T>) {
        // Every SharedRef reports the type it wraps, so JavaScript can tell refs apart.
        constant("nativeRefType", [](const detail::SharedRefBase& self) { return self.nativeRefType(); });
      }
    }

    /** The name JavaScript sees for the class. The class's C++ name by default. */
    void name(std::string value) {
      spec_.name = std::move(value);
      names_.setOwner(spec_.name);
    }

    /** Makes the class constructible from JavaScript as `new Class(args...)`, with `T(args...)`. */
    template<typename... Args>
    void constructor() {
      constructor(detail::MakeShared<T, Args...>{});
    }

    /** Makes the class constructible with [factory], which returns a `std::shared_ptr<T>`. */
    template<detail::Callable F>
    void constructor(F&& factory) {
      static_assert(
        std::is_same_v<std::shared_ptr<T>, typename detail::CallableTraits<std::decay_t<F>>::Return>,
        "A constructor factory returns a std::shared_ptr<T> of the class it constructs"
      );
      spec_.constructor = detail::makeFunction<void>(spec_.name, std::forward<F>(factory));
    }

    /** Makes the class inherit the members of [Base], and an instance acceptable where [Base] is asked for. */
    template<detail::SharedObjectType Base>
    void extends() {
      static_assert(std::is_base_of_v<Base, T>, "extends<Base>: the class has to derive from Base");
      spec_.parent = &detail::classSpecOf<Base>();
    }

    template<detail::Callable F>
    void function(std::string name, F&& callable) {
      names_.claim(name);
      spec_.functions.push_back(member(detail::makeFunction<T>(std::move(name), std::forward<F>(callable))));
    }

    template<detail::MemberFunctionPointer M>
    void function(std::string name, M method) {
      function(std::move(name), detail::UnboundMethod<std::decay_t<M>>{method});
    }

    template<detail::Callable G>
    void property(std::string name, G&& getter) {
      names_.claim(name);
      spec_.properties.push_back(detail::PropertySpec{
        .name = name,
        .getter = member(detail::makeFunction<T>(name, std::forward<G>(getter))),
        .setter = std::nullopt,
      });
    }

    template<detail::Callable G, detail::Callable S>
    void property(std::string name, G&& getter, S&& setter) {
      names_.claim(name);
      spec_.properties.push_back(detail::PropertySpec{
        .name = name,
        .getter = member(detail::makeFunction<T>(name, std::forward<G>(getter))),
        .setter = member(detail::makeFunction<T>(name, std::forward<S>(setter))),
      });
    }

    /** A property backed by a data member; read-only when the member is const. */
    template<detail::MemberObjectPointer M>
    void property(std::string name, M member) {
      using Value = typename detail::DataMember<std::decay_t<M>>::Value;
      using Owner = typename detail::DataMember<std::decay_t<M>>::Class;
      static_assert(std::is_base_of_v<Owner, T>, "The member belongs to another class");

      auto reader = detail::MemberReader<Owner, Value>{member};
      if constexpr (std::is_const_v<Value>) {
        property(std::move(name), std::move(reader));
      } else {
        property(std::move(name), std::move(reader), detail::MemberWriter<Owner, Value>{member});
      }
    }

    template<detail::MemberFunctionPointer G>
    void property(std::string name, G getter) {
      property(std::move(name), detail::UnboundMethod<std::decay_t<G>>{getter});
    }

    template<detail::MemberFunctionPointer G, detail::MemberFunctionPointer S>
    void property(std::string name, G getter, S setter) {
      property(
        std::move(name),
        detail::UnboundMethod<std::decay_t<G>>{getter},
        detail::UnboundMethod<std::decay_t<S>>{setter}
      );
    }

    /**
     * A constant. A value is the same for every instance. A callable is a getter run at the first
     * read of each instance: JavaScript keeps the result on that instance, and the getter is not
     * called for it again.
     */
    template<typename V>
      requires(!detail::MemberFunctionPointer<V>)
    void constant(std::string name, V&& valueOrGetter) {
      names_.claim(name);
      if constexpr (detail::Callable<V>) {
        spec_.constants.push_back(detail::ConstantSpec{
          .name = name,
          .getter = member(detail::makeFunction<T>(name, std::forward<V>(valueOrGetter))),
          .lazy = true,
        });
      } else {
        using Stored = detail::ConstantStorage<V>;
        spec_.constants.push_back(detail::ConstantSpec{
          .name = name,
          .getter = detail::makeFunction<void>(name, detail::ConstantValue<Stored>{Stored(std::forward<V>(valueOrGetter))}),
          .lazy = false,
        });
      }
    }

    template<detail::MemberFunctionPointer G>
    void constant(std::string name, G getter) {
      constant(std::move(name), detail::UnboundMethod<std::decay_t<G>>{getter});
    }

  private:
    /** Marks [function] as a member of this class: its receiver is checked against it. */
    detail::FunctionSpec member(detail::FunctionSpec function) {
      function.receiverClass = &spec_;
      return function;
    }

    detail::ClassSpec& spec_;
    detail::ExportNames names_;
  };
} // namespace expo::modules::v2::cpp

namespace expo::modules::v2::cpp::detail {
  /** Builds the table for [T] from its `define`. Called once. */
  template<typename T>
  const ClassSpec* buildClassSpec() {
    static_assert(
      requires(ClassDefinition<T>& definition) { T::define(definition); },
      "A shared object class describes itself with `static void define(cpp::ClassDefinition<T>&)`"
    );

    auto* created = new ClassSpec();
    created->name = std::string(typeName<T>());
    ClassDefinition<T> definition(*created);
    T::define(definition);
    if (created->constructor.has_value()) {
      // Named for the class JavaScript sees, whatever order `name` and `constructor` were called in.
      created->constructor->name = created->name;
    }
    return created;
  }

  template<typename T>
  const ClassSpec& classSpecOf() {
    // Built once and kept for the life of the process: every runtime builds its prototype from the
    // same table, and a class object held by JavaScript can outlive any one runtime's setup.
    static const ClassSpec* const spec = buildClassSpec<T>();
    return *spec;
  }
} // namespace expo::modules::v2::cpp::detail
