#pragma once

#include <algorithm>
#include <cstddef>
#include <mutex>
#include <string>
#include <string_view>
#include <type_traits>
#include <utility>
#include <vector>

#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/Definition.h>
#include <expo-modules-cpp/Event.h>
#include <expo-modules-cpp/Module.h>
#include <expo-modules-cpp/SharedObject.h>
#include <expo-modules-cpp/internal/Macros.h>

// Annotations: describe what JavaScript sees where the member is declared, the way Kotlin's `@JS`
// does, instead of listing it all in `define`.
//
//   class Settings : public cpp::ExpoModule<Settings> {
//   public:
//     EXPO_JS(add)
//     int add(int a, int b) { return a + b; }
//
//     EXPO_JS(volume)
//     double volume = 1;
//
//     EXPO_JS_CONSTANT(version)
//     static constexpr int version = 3;
//
//     EXPO_EVENT(onChanged, int);
//   };
//   EXPO_MODULE(Settings)
//
// C++ cannot attach a queryable annotation to a declaration (C++26 reflection will), so an
// annotation is a macro written above the member, and it repeats the member's name. The macros only
// add to what `define` does, through the same `cpp::ModuleDefinition` and `cpp::ClassDefinition`, so
// every rule of those applies: a name must be unique, a member function is called on the module or
// on the shared object it was called on, and so on.

namespace expo::modules::v2::cpp {
  namespace detail {
    /** `onChanged` is subscribed to as `changed`; any other name is used as it is. */
    EXPO_API std::string eventNameOf(std::string_view member);

    /** `getVolume` is the property `volume`; any other name is used as it is. */
    EXPO_API std::string propertyNameOf(std::string_view getter);

    template<typename T>
    inline constexpr bool kAlwaysFalse = false;

    /**
     * What annotations export to: the module being defined, or the shared object class being
     * described. One interface over both, so an annotation does not care which it is in.
     */
    template<typename Self>
    class ExportTarget {
    public:
      static constexpr bool kModule = std::is_base_of_v<Module, Self>;

      ExportTarget(ModuleDefinition& definition, Self& self) : module_(&definition), self_(&self) {
      }

      explicit ExportTarget(ClassDefinition<Self>& definition) : class_(&definition) {
      }

      /** `EXPO_JS`: a function for a function, a property for a data member. */
      template<typename M>
      void exportJS(std::string name, M pointer) {
        if constexpr (std::is_member_function_pointer_v<M>) {
          if constexpr (kModule) {
            module_->function(std::move(name), self_, pointer);
          } else {
            class_->function(std::move(name), pointer);
          }
        } else if constexpr (std::is_member_object_pointer_v<M>) {
          if constexpr (kModule) {
            module_->property(std::move(name), self_, pointer);
          } else {
            class_->property(std::move(name), pointer);
          }
        } else if constexpr (std::is_pointer_v<M> && std::is_function_v<std::remove_pointer_t<M>>) {
          if constexpr (kModule) {
            module_->function(std::move(name), pointer);
          } else {
            class_->function(std::move(name), pointer);
          }
        } else {
          static_assert(
            kAlwaysFalse<M>,
            "EXPO_JS marks a member function, a data member or a static function; "
            "a static data member is a constant, so use EXPO_JS_CONSTANT"
          );
        }
      }

      /** `EXPO_JS_GETTER`: a property JavaScript can read. */
      template<typename G>
      void exportGetter(std::string name, G getter) {
        static_assert(
          std::is_member_function_pointer_v<G> || std::is_function_v<std::remove_pointer_t<G>>,
          "A getter is a member function or a static function"
        );
        if constexpr (std::is_member_function_pointer_v<G>) {
          if constexpr (kModule) {
            module_->property(std::move(name), self_, getter);
          } else {
            class_->property(std::move(name), getter);
          }
        } else {
          if constexpr (kModule) {
            module_->property(std::move(name), getter);
          } else {
            class_->property(std::move(name), getter);
          }
        }
      }

      /** `EXPO_JS_PROPERTY`: a property JavaScript can read and write. */
      template<typename G, typename S>
      void exportProperty(std::string name, G getter, S setter) {
        static_assert(
          std::is_member_function_pointer_v<G> && std::is_member_function_pointer_v<S>,
          "EXPO_JS_PROPERTY names a getter and a setter, both member functions"
        );
        if constexpr (kModule) {
          module_->property(std::move(name), self_, getter, setter);
        } else {
          class_->property(std::move(name), getter, setter);
        }
      }

      /**
       * `EXPO_JS_CONSTANT`: a constant. A function or a data member is read at the first read of
       * the constant, a static data member now.
       */
      template<typename M>
      void exportConstant(std::string name, M pointer) {
        if constexpr (std::is_member_function_pointer_v<M>) {
          if constexpr (kModule) {
            module_->constant(std::move(name), self_, pointer);
          } else {
            class_->constant(std::move(name), pointer);
          }
        } else if constexpr (std::is_member_object_pointer_v<M>) {
          if constexpr (kModule) {
            module_->constant(std::move(name), [self = self_, pointer] { return self->*pointer; });
          } else {
            class_->constant(std::move(name), [pointer](Self& self) { return self.*pointer; });
          }
        } else if constexpr (std::is_pointer_v<M> && std::is_function_v<std::remove_pointer_t<M>>) {
          if constexpr (kModule) {
            module_->constant(std::move(name), pointer);
          } else {
            class_->constant(std::move(name), pointer);
          }
        } else if constexpr (std::is_pointer_v<M>) {
          if constexpr (kModule) {
            module_->constant(std::move(name), *pointer);
          } else {
            class_->constant(std::move(name), *pointer);
          }
        } else {
          static_assert(kAlwaysFalse<M>, "EXPO_JS_CONSTANT marks a function, a data member or a static data member");
        }
      }

      /** `EXPO_JS_CONSTRUCTOR`. */
      template<typename... Args>
      void exportConstructor() {
        static_assert(!kModule, "A constructor belongs to a shared object class, not a module");
        if constexpr (!kModule) {
          class_->template constructor<Args...>();
        }
      }

      /** `EXPO_JS_CLASS`: a shared object class the module exports. */
      template<typename T>
      void exportClass(std::string name) {
        static_assert(kModule, "A module exports shared object classes; a shared object class cannot");
        if constexpr (kModule) {
          module_->template sharedClass<T>(std::move(name));
        }
      }

      /** `EXPO_JS_NAME`. */
      void setName(std::string name) {
        static_assert(!kModule, "A module is named where it is registered, with EXPO_MODULE(Type, \"Name\")");
        if constexpr (!kModule) {
          class_->name(std::move(name));
        }
      }

    private:
      ModuleDefinition* module_ = nullptr;
      Self* self_ = nullptr;
      ClassDefinition<Self>* class_ = nullptr;
    };

    /** The annotations of [Self], in the order they are declared. */
    template<typename Self>
    struct ExportList {
      using Apply = void (*)(ExportTarget<Self>&);

      struct Entry {
        /** The ones that name or shape the class run first, so the rest see the final name. */
        int phase;
        Apply apply;
      };

      std::mutex mutex;
      std::vector<Entry> entries;
    };

    template<typename Self>
    ExportList<Self>& exportList() {
      // Never destroyed: the annotations register while the library loads, and a destructor racing
      // another library's initializers is a crash at exit for nothing.
      static ExportList<Self>& list = *new ExportList<Self>();
      return list;
    }

    struct Registered {};

    template<typename Self>
    Registered registerExport(typename ExportList<Self>::Apply apply, const int phase) {
      ExportList<Self>& list = exportList<Self>();
      const std::lock_guard lock(list.mutex);
      list.entries.push_back({.phase = phase, .apply = apply});
      return {};
    }

    template<typename Self>
    void applyExports(ExportTarget<Self>& target) {
      ExportList<Self>& list = exportList<Self>();
      std::vector<typename ExportList<Self>::Entry> entries;
      {
        const std::lock_guard lock(list.mutex);
        entries = list.entries;
      }
      std::ranges::stable_sort(entries, {}, &ExportList<Self>::Entry::phase);
      for (const auto& entry: entries) {
        entry.apply(target);
      }
    }
  } // namespace detail

  /**
   * A module whose exports are annotations. Derive from it with the class itself as [Self]:
   *
   * ```cpp
   * class Settings : public cpp::ExpoModule<Settings> {
   * public:
   *   EXPO_JS(reset)
   *   void reset() { ... }
   * };
   * EXPO_MODULE(Settings)
   * ```
   *
   * It is a `cpp::Module`, and its `define` exports the annotated members. A module that also wants
   * exports of its own overrides `define`, calls this one, and adds them.
   */
  template<typename Self>
  class ExpoModule : public Module {
  public:
    /** What the annotations in the class body refer to. */
    using ExpoSelf = Self;

    void define(ModuleDefinition& definition) override {
      detail::ExportTarget<Self> target(definition, static_cast<Self&>(*this));
      detail::applyExports(target);
    }

  protected:
    ExpoModule() = default;
  };

  /**
   * A shared object class whose exports are annotations: Kotlin's `@ExpoSharedObject`. Derive from
   * it with the class itself as [Self], and with the class it extends as [Base], if any:
   *
   * ```cpp
   * class Ticket : public cpp::ExpoSharedObject<Ticket> {
   * public:
   *   EXPO_JS_CONSTRUCTOR(std::string)
   *   explicit Ticket(std::string text) : text_(std::move(text)) {}
   *
   *   EXPO_JS(text)
   *   std::string text_;
   * };
   *
   * class Dog : public cpp::ExpoSharedObject<Dog, Animal> { ... };
   * ```
   *
   * The module that exports the class says so with `EXPO_JS_CLASS(Ticket)`.
   */
  template<typename Self, typename Base = SharedObject>
  class ExpoSharedObject : public Base {
  public:
    /** What the annotations in the class body refer to. */
    using ExpoSelf = Self;

    static void define(ClassDefinition<Self>& definition) {
      if constexpr (!std::is_same_v<Base, SharedObject> && requires { Base::define(std::declval<ClassDefinition<Base>&>()); }) {
        definition.template extends<Base>();
      }
      detail::ExportTarget<Self> target(definition);
      detail::applyExports(target);
    }

    using Base::Base;

  protected:
    ExpoSharedObject() = default;
  };
} // namespace expo::modules::v2::cpp

// --- the annotations --------------------------------------------------------------------------------
//
// Each one defines, in the class, a function that exports its member and a static that registers
// that function when the library loads. The function is a member, so it sees the class whole - a
// member declared after the annotation, or a private one, is found - and the registration is a
// plain function pointer in a list that belongs to the class. The function is named after the member
// and the line, so annotations of different members may share a line; two of the same kind that
// export no member - two `EXPO_JS_CLASS` - may not.

#define EXPO_DETAIL_NS ::expo::modules::v2::cpp

#define EXPO_DETAIL_EXPORT(Id, Phase, ...) \
  static void EXPO_DETAIL_CAT(EXPO_DETAIL_CAT(expoApply_, Id), __LINE__)( \
    EXPO_DETAIL_NS::detail::ExportTarget<ExpoSelf>& target \
  ) { \
    __VA_ARGS__ \
  } \
  inline static const EXPO_DETAIL_NS::detail::Registered EXPO_DETAIL_CAT(EXPO_DETAIL_CAT(expoRegistered_, Id), __LINE__) = \
    EXPO_DETAIL_NS::detail::registerExport<ExpoSelf>( \
      &EXPO_DETAIL_CAT(EXPO_DETAIL_CAT(expoApply_, Id), __LINE__), \
      Phase \
    );

// Picks the macro for one, two or three arguments: an optional trailing argument is the name.
#define EXPO_DETAIL_BY_COUNT(_1, _2, _3, Macro, ...) Macro
#define EXPO_DETAIL_ONE_OR_TWO(One, Two, ...) \
  EXPO_DETAIL_BY_COUNT(__VA_ARGS__, EXPO_DETAIL_TOO_MANY, Two, One, unused)(__VA_ARGS__)
#define EXPO_DETAIL_TWO_OR_THREE(Two, Three, ...) \
  EXPO_DETAIL_BY_COUNT(__VA_ARGS__, Three, Two, EXPO_DETAIL_TOO_FEW, unused)(__VA_ARGS__)
#define EXPO_DETAIL_TOO_MANY(...) \
  static_assert(sizeof(#__VA_ARGS__) == 0, "This annotation takes the member and, optionally, a name: a string");
#define EXPO_DETAIL_TOO_FEW(...) \
  static_assert(sizeof(#__VA_ARGS__) == 0, "EXPO_JS_PROPERTY takes a getter, a setter and, optionally, a name: a string");

/**
 * Exports the member declared below it - a member function as a function, a data member as a
 * property, a static function as a function - under its own name, or under [Name], a string:
 *
 * ```cpp
 * EXPO_JS(add)
 * int add(int a, int b);
 *
 * EXPO_JS(count, "total")
 * int count = 0;
 * ```
 *
 * A data member is read-write, or read-only when it is `const`. An overloaded function cannot be
 * named this way: give the overloads different names.
 */
#define EXPO_JS(...) EXPO_DETAIL_ONE_OR_TWO(EXPO_DETAIL_JS_1, EXPO_DETAIL_JS_2, __VA_ARGS__)
#define EXPO_DETAIL_JS_1(Member) EXPO_DETAIL_EXPORT(Member, 1, target.exportJS(#Member, &ExpoSelf::Member);)
#define EXPO_DETAIL_JS_2(Member, Name) \
  EXPO_DETAIL_EXPORT(Member, 1, target.exportJS(std::string(Name), &ExpoSelf::Member);)

/**
 * Exports the member function [Getter] as a property JavaScript reads. Its name is the getter's
 * without a leading `get`: `getVolume` is `volume`. A second argument, a string, names it otherwise.
 *
 * ```cpp
 * EXPO_JS_GETTER(getVolume)
 * double getVolume() const;
 * ```
 */
#define EXPO_JS_GETTER(...) EXPO_DETAIL_ONE_OR_TWO(EXPO_DETAIL_GETTER_1, EXPO_DETAIL_GETTER_2, __VA_ARGS__)
#define EXPO_DETAIL_GETTER_1(Getter) \
  EXPO_DETAIL_EXPORT(Getter, 1, target.exportGetter(EXPO_DETAIL_NS::detail::propertyNameOf(#Getter), &ExpoSelf::Getter);)
#define EXPO_DETAIL_GETTER_2(Getter, Name) \
  EXPO_DETAIL_EXPORT(Getter, 1, target.exportGetter(std::string(Name), &ExpoSelf::Getter);)

/**
 * Exports a getter and a setter, both member functions, as one property JavaScript reads and writes.
 * Its name is the getter's without a leading `get`; a third argument, a string, names it otherwise.
 * Written above either of the two.
 *
 * ```cpp
 * EXPO_JS_PROPERTY(getVolume, setVolume)
 * double getVolume() const;
 * void setVolume(double value);
 * ```
 */
#define EXPO_JS_PROPERTY(...) EXPO_DETAIL_TWO_OR_THREE(EXPO_DETAIL_PROPERTY_2, EXPO_DETAIL_PROPERTY_3, __VA_ARGS__)
#define EXPO_DETAIL_PROPERTY_2(Getter, Setter) \
  EXPO_DETAIL_EXPORT( \
    Getter, \
    1, \
    target.exportProperty(EXPO_DETAIL_NS::detail::propertyNameOf(#Getter), &ExpoSelf::Getter, &ExpoSelf::Setter); \
  )
#define EXPO_DETAIL_PROPERTY_3(Getter, Setter, Name) \
  EXPO_DETAIL_EXPORT(Getter, 1, target.exportProperty(std::string(Name), &ExpoSelf::Getter, &ExpoSelf::Setter);)

/**
 * Exports the member declared below it as a constant: JavaScript reads it once and keeps the value
 * as a plain read-only property. A member function or a data member is read at the first read, a
 * shared object's once per instance; a static data member is read when the class is described.
 *
 * ```cpp
 * EXPO_JS_CONSTANT(version)
 * static constexpr int version = 3;
 * ```
 */
#define EXPO_JS_CONSTANT(...) EXPO_DETAIL_ONE_OR_TWO(EXPO_DETAIL_CONSTANT_1, EXPO_DETAIL_CONSTANT_2, __VA_ARGS__)
#define EXPO_DETAIL_CONSTANT_1(Member) EXPO_DETAIL_EXPORT(Member, 1, target.exportConstant(#Member, &ExpoSelf::Member);)
#define EXPO_DETAIL_CONSTANT_2(Member, Name) \
  EXPO_DETAIL_EXPORT(Member, 1, target.exportConstant(std::string(Name), &ExpoSelf::Member);)

/**
 * Makes a shared object class constructible from JavaScript with `new`. It lists the types of the
 * constructor's parameters and is written above that constructor; a class has one.
 *
 * ```cpp
 * EXPO_JS_CONSTRUCTOR(std::string, int)
 * Ticket(std::string text, int copies);
 * ```
 */
#define EXPO_JS_CONSTRUCTOR(...) EXPO_DETAIL_EXPORT(Constructor, 1, target.template exportConstructor<__VA_ARGS__>();)

/** The name JavaScript sees for a shared object class, which is the class's own name otherwise. */
#define EXPO_JS_NAME(Name) EXPO_DETAIL_EXPORT(JsName, 0, target.setName(Name);)

/**
 * In a module: exports the shared object class [Type], constructible from JavaScript, under its own
 * name or under [Name] - Kotlin's `@ExpoModule(classes = [...])`.
 */
#define EXPO_JS_CLASS(...) EXPO_DETAIL_ONE_OR_TWO(EXPO_DETAIL_CLASS_1, EXPO_DETAIL_CLASS_2, __VA_ARGS__)
#define EXPO_DETAIL_CLASS_1(Type) EXPO_DETAIL_EXPORT(Class, 1, target.template exportClass<Type>(std::string());)
#define EXPO_DETAIL_CLASS_2(Type, Name) \
  EXPO_DETAIL_EXPORT(Class, 1, target.template exportClass<Type>(std::string(Name));)

/**
 * Declares an event member and exports it: `EXPO_EVENT(onChanged, int);` is a
 * `cpp::Event<int> onChanged` that JavaScript subscribes to as `changed`. A leading `on` followed by
 * a capital letter is dropped, as in Kotlin; the payload types follow, none for an event without
 * one. An event that needs its own name or hooks is a plain `cpp::Event` member.
 */
#define EXPO_EVENT(Member, ...) \
  EXPO_DETAIL_NS::Event<__VA_ARGS__> Member { this, EXPO_DETAIL_NS::detail::eventNameOf(#Member) }
