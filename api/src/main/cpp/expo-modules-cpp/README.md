# Expo Modules API v2 for C++

Write a native module once, as plain C++, and call it from JavaScript. Same features as the Kotlin
API — functions, properties, constants, events, shared objects, records, enums, promises, typed
arrays — with no JNI between JavaScript and your code: a call is a JSI host function that converts
its arguments, calls your C++, and converts the result.

```cpp
#include <expo-modules-cpp/Expo.h>

namespace cpp = expo::modules::v2::cpp;

class MathModule : public cpp::Module {
public:
  void define(cpp::ModuleDefinition& def) override {
    def.constant("PI", 3.14159);
    def.function("add", [](int a, int b) { return a + b; });
    def.function("scale", this, &MathModule::scale);
    def.property("factor", [this] { return factor_; }, [this](double value) { factor_ = value; });
  }

private:
  double scale(double value) const { return value * factor_; }

  double factor_ = 2;
};

EXPO_MODULE(MathModule)
```

```js
expo.modules.MathModule.add(1, 2); // 3
```

It is fast: a call costs what a hand-written host function costs (see [Performance](#performance)),
and easy: the library reads the signature of what you export, so there is nothing to describe twice.

- [Defining a module](#defining-a-module)
- [Annotations](#annotations)
- [Types](#types)
- [Records and enums](#records-and-enums)
- [Shared objects](#shared-objects)
- [Events](#events)
- [Async: promises and coroutines](#async-promises-and-coroutines)
- [Errors](#errors)
- [Threads and lifetimes](#threads-and-lifetimes)
- [Using it](#using-it)
- [Kotlin and C++ side by side](#kotlin-and-c-side-by-side)
- [Performance](#performance)
- [Tests](#tests)

## Defining a module

A module derives from `cpp::Module` and describes its exports in `define`. `EXPO_MODULE(Type)` makes
it available as `expo.modules.<Type>`; `EXPO_MODULE(Type, "Name")` names it otherwise. The module is
created the first time something asks for it, and lives until its context closes.

| In `define` | JavaScript sees |
| --- | --- |
| `def.function("name", callable)` | a function |
| `def.function("name", this, &Module::method)` | a function that calls a method of the module |
| `def.property("name", getter)` / `(name, getter, setter)` | an accessor, read (and written) on every access |
| `def.property("name", this, &Module::member)` | an accessor backed by a data member; read-only if the member is `const` |
| `def.constant("name", value)` | a read-only value, set when the module object is built |
| `def.constant("name", getter)` | a read-only value computed at the first read, once |
| `def.sharedClass<Counter>()` | a class `new expo.modules.M.Counter(...)` |

A callable is a lambda, a function, a functor, or a method; the library reads its signature. Each
parameter is converted from the JavaScript argument, the result is converted back, and an exception
becomes a JavaScript `Error`. A call with the wrong number of arguments fails with
`add expects 2 argument(s), but received 1` — except that trailing `std::optional` parameters can be
left out.

Two parameter types take no JavaScript argument:

- `js::Runtime&` — the runtime the call came from. `namespace js = facebook::jsi;`.
- `cpp::Promise<T>` — see [Async](#async-promises-and-coroutines).

`const js::Value&` borrows the argument itself, with no conversion and no copy. Any other `js::`
type (`js::Object`, `js::Function`, `js::Array`, `js::ArrayBuffer`, ...) is checked and passed.

A name must be unique in its module, and `addListener`, `removeListener`, `removeAllListeners`,
`listenerCount` and `emit` are the event emitter's.

## Annotations

`define` lists what JavaScript sees in one place. If you would rather mark each member where it is
declared, the way Kotlin's `@JS` does, derive from `cpp::ExpoModule<Self>` (or
`cpp::ExpoSharedObject<Self>`) and write an annotation above the member:

```cpp
class Counter : public cpp::ExpoSharedObject<Counter> {
public:
  EXPO_JS_CONSTRUCTOR(int)                       // new Counter(5)
  explicit Counter(int start) : value_(start) {}

  EXPO_JS(increment)
  int increment() { return ++value_; }

  EXPO_JS(value_, "value")                       // a name of its own
  int value_;

  EXPO_JS_CONSTANT(kind)                         // same for every instance
  static constexpr const char* kind = "counter";

  EXPO_JS_CONSTANT(label)                        // computed at the first read of each instance
  std::string label() { return "#" + std::to_string(value_); }

  EXPO_EVENT(onChanged, int);                    // subscribed to as 'changed'
};

class Counting : public cpp::ExpoModule<Counting> {
public:
  EXPO_JS_CLASS(Counter)                         // new expo.modules.Counting.Counter(...)

  EXPO_JS(make)
  std::shared_ptr<Counter> make() { return std::make_shared<Counter>(1); }

  EXPO_JS(bump)
  static int bump(Counter& counter) { return counter.increment(); }
};

EXPO_MODULE(Counting)
```

| Kotlin | C++ |
| --- | --- |
| `@ExpoModule class M : Module()` | `class M : public cpp::ExpoModule<M>` and `EXPO_MODULE(M)` |
| `@ExpoSharedObject class S` | `class S : public cpp::ExpoSharedObject<S>`; `<S, Base>` for a class that extends `Base` |
| `@ExpoModule(classes = [S::class])` | `EXPO_JS_CLASS(S)` or `EXPO_JS_CLASS(S, "Name")` in the module |
| `@ExpoSharedObject(name = "N")` | `EXPO_JS_NAME("N")` in the class |
| `@JS fun f()` | `EXPO_JS(f)`, above a member function or a static function |
| `@JS(name = "g") fun f()` | `EXPO_JS(f, "g")` |
| `@JS val`, `@JS var` | `EXPO_JS(x)`, above a data member; read-only when it is `const` |
| `@JS val x get() = ...` | `EXPO_JS_GETTER(getX)`: `x`, without the `get` |
| `@JS var x get() = ... set(v) {}` | `EXPO_JS_PROPERTY(getX, setX)`, or `(getX, setX, "name")` |
| `@JS @Constant val` | `EXPO_JS_CONSTANT(x)`, above a data member, a getter or a static data member |
| `@JS constructor(...)` | `EXPO_JS_CONSTRUCTOR(ArgTypes...)`, above the constructor |
| `@Event val onX = event<T>()` | `EXPO_EVENT(onX, T);` declares the event; `onX` is `x` to JavaScript |

The annotations only add to what `define` does. Each one calls the same `def.function`, `def.property`
and `def.constant` underneath, so every rule above still holds, and the two styles mix: a module
that overrides `define` calls `cpp::ExpoModule<M>::define(def)` first, then adds its own exports.
A [record](#records-and-enums) is already one line, `EXPO_RECORD`, and stays one.

C++ cannot attach a queryable annotation to a declaration — C++26 reflection will — so these are
macros, and they differ from Kotlin's in four ways:

- **The name is repeated.** `EXPO_JS(add)` above `add` is how the macro finds the member; a typo is a
  compile error. An overloaded function cannot be named this way: give the overloads different
  names, or export it in `define` with a cast.
- **The class names itself** as the template argument of `ExpoModule` / `ExpoSharedObject`.
  A class template cannot use them.
- **The annotation sits above the member, in the class**, in any access section. It must be the
  class that derives from `ExpoModule` / `ExpoSharedObject` directly.
- **`EXPO_MODULE(M)` still registers a module**, and `EXPO_JS_CLASS` still lists the classes it
  exports: C++ cannot find them by scanning.

An annotation registers itself as the library loads, like `EXPO_MODULE` does, and a static library
that holds only an annotated class has to be linked whole.

## Types

| C++ | JavaScript |
| --- | --- |
| `bool` | boolean |
| `int`, `unsigned`, `int64_t`, `uint8_t`, ... | number. A fraction is dropped toward zero (`-0.5` is `0`); `NaN`, infinity and anything out of range is an error. 64-bit integers are exact up to 2^53, as a Kotlin `Long` is. |
| `float`, `double` | number |
| `std::string` | string (UTF-8). `std::string_view` and `const char*` are results only. |
| `std::optional<T>` | `T` or `null`; a trailing parameter can be left out |
| `std::vector<T>`, `std::array<T, N>`, `std::set<T>`, `std::unordered_set<T>` | array |
| `std::pair`, `std::tuple` | fixed-length array |
| `std::map<std::string, T>`, `std::unordered_map<std::string, T>` | object |
| `std::vector<uint8_t>` | `ArrayBuffer`. A parameter also takes a typed array or a `DataView`. A list of numbers is a `std::vector<int>`. |
| `std::variant<A, B>` | the first alternative the value converts to; an integer alternative takes a fraction only when no other one wants the value, so `variant<int, double>` gets `3.5` as the `double` |
| `std::chrono::duration` | number of seconds; with an integer count, a value that does not fit is an error, as for any integer |
| `std::filesystem::path` | string |
| `cpp::Any` | any JSON-like value |
| `cpp::TypedArray` | a typed array or `DataView`, viewed in place |
| `std::shared_ptr<T>`, `T&`, `T*` of a shared object | the object |
| a struct with `EXPO_RECORD` | object |
| an enum with `EXPO_ENUM` | string or number |

Teach the library another type by specializing `cpp::Converter<T>`:

```cpp
template<>
struct expo::modules::v2::cpp::Converter<Color> {
  static Color fromJS(js::Runtime& rt, const js::Value& value);   // throws cpp::ConversionError
  static js::Value toJS(js::Runtime& rt, const Color& value);
};
```

A bad argument is reported with what it was and what was needed:
`Invalid argument 2 of 'add': expected a number, got a string`.

## Records and enums

Describe a struct or an enum once, in the namespace it is in, after it:

```cpp
struct Options {
  std::string title;
  int retries = 3;
  std::optional<std::string> subtitle;
};
EXPO_RECORD(Options, title, (retries, optional), subtitle)

enum class Style { Light, Heavy };
EXPO_ENUM(Style, (Light, "light"), (Heavy, "heavy"))   // 'light' / 'heavy'

enum class Priority { Low = 0, High = 10 };
EXPO_ENUM(Priority, (Low, 0), (High, 10))              // 0 / 10

enum class Size { Small, Large };
EXPO_ENUM(Size, Small, Large)                          // 'Small' / 'Large'
```

A record is default-constructed and then filled from the JavaScript object. A field marked
`optional` may be missing and then keeps its default; a `std::optional` field may be missing or
`null`; any other missing field is an error that names it. An enum value that is not listed is an
error that lists the ones that are. Up to 32 fields or entries.

## Shared objects

A native object JavaScript holds a reference to, rather than a value copied across. Derive from
`cpp::SharedObject`, describe the class in a static `define`, and hand instances out as
`std::shared_ptr`:

```cpp
class Counter : public cpp::SharedObject {
public:
  static void define(cpp::ClassDefinition<Counter>& cls) {
    cls.constructor<int>();                                   // new Counter(5)
    cls.function("increment", &Counter::increment);
    cls.function("reset", [](Counter& self) { self.value_ = 0; });
    cls.property("value", &Counter::value_);
    cls.constant("kind", "counter");                          // same for every instance
    cls.constant("label", [](Counter& self) { return "#" + std::to_string(self.value_); });
  }

  explicit Counter(int start) : value_(start) {}
  int increment() { return ++value_; }

  int value_;
  cpp::Event<int> onChanged{this, "changed"};
};

// in a module's define:
def.sharedClass<Counter>();
def.function("make", [] { return std::make_shared<Counter>(1); });
def.function("bump", [](Counter& counter) { return counter.increment(); });
```

- The same native object is the same JavaScript object, for as long as JavaScript holds it.
- A callable's first parameter is the object it was called on when it is `C&`, `const C&`, `C*` or
  `std::shared_ptr<C>`. Parameters of the class elsewhere are arguments: `T&` refuses `null`,
  `T*` and `std::shared_ptr<T>` take it.
- A constant given as a getter runs at the first read of each instance and is then the instance's
  own read-only value.
- `release()` in JavaScript, or `object->release()` natively, calls `didRelease()` once and makes the
  object refuse further calls. The destructor runs when the last reference — JavaScript's or
  native — goes, possibly on the garbage collector's thread, so it must not touch JavaScript.
- `cls.extends<Base>()` gives a class its base's members and lets an instance go where a `Base` is
  declared.
- `cpp::SharedRef<T>` is a handle to a native value that JavaScript can only pass back. It exports
  `nativeRefType`.
- `externalMemoryPressure()` reports bytes the collector cannot see.
- A class without a constructor is not exposed to `new`: JavaScript only receives its instances.

Where this differs from what a JavaScript or Kotlin author might expect:

- The class JavaScript sees is the one the object **first crosses as**. The library does not use
  RTTI, so it cannot ask a `std::shared_ptr<Animal>` what it really holds: a `Dog` returned as
  `std::shared_ptr<Animal>` before it ever crossed as a `Dog` is an `Animal` in JavaScript, and stays
  one. Return it as `std::shared_ptr<Dog>`, or construct it from JavaScript.
- A class's members are installed on its prototype when its first instance is made, as the Kotlin API
  does, so `Counter.prototype.increment` is `undefined` until then.
- `new` always returns the native constructor's own object, as in the Kotlin API:
  `class Mine extends expo.modules.Counters.Counter {}` gives `new Mine()` a plain `Counter`, which is
  not an `instanceof Mine`.
- An object that carries a native state of some other library (anything attached with
  `jsi::Object::setNativeState` that does not go through `expo::jsi::ChainedNativeState`) must not be
  passed where a module or shared object is expected: `expo-jsi` cannot recognise a foreign state
  without RTTI. This is the same for the Kotlin API.

## Events

```cpp
cpp::Event<std::string> onStateChange{this, "stateChange"};
cpp::Event<int, std::string> onProgress{this, "progress", {
  .onStartObserving = [this] { startWork(); },
  .onStopObserving = [this] { stopWork(); },
}};

onStateChange.emit("playing");   // or onStateChange("playing")
```

An event is a member of a module or shared object, constructed with `this` and the name JavaScript
subscribes to. JavaScript uses `addListener(name, listener)` — which returns `{ remove() }` —
`removeListener`, `removeAllListeners`, `listenerCount` and `emit`, on the module and on every
shared object. Each value of the payload is one argument of the listener.

`emit` is safe from any thread: it is delivered at once when called on the JS thread of a listening
runtime, and posted to it otherwise, and the payload is converted there. `onStartObserving` runs
when the first listener in any runtime subscribes, `onStopObserving` after the last one leaves — or
its runtime was torn down, or the object was released. A listener that throws is logged and does not
stop the others, and so is a hook that throws.

- Listeners are kept on the emitter's own JavaScript object, not in a table of the library's, so a
  listener that mentions its emitter (`p.addListener('x', () => p.value)`) does not keep it alive:
  the two are a cycle the collector takes, and the native object goes with them.
- A subscription's `remove()` takes off the one registration it stands for, however often it is
  called, and does nothing once the emitter is gone. After `release()`, `remove()`,
  `removeListener` and `removeAllListeners` still work; `addListener` and `emit` throw.
- An event's payload types are values: `Event<std::string>`, not `Event<const std::string&>` - the
  payload outlives the `emit` call, and a reference would not.

## Async: promises and coroutines

A function that takes a `cpp::Promise` or returns a `cpp::Task` returns a promise to JavaScript.
Arguments are still converted, and checked, at the call.

```cpp
// A promise to settle yourself, from any thread. The first settle counts.
def.function("load", [](std::string url, cpp::Promise<std::string> promise) {
  pool.submit([=] {
    try { promise.resolve(download(url)); }
    catch (const std::exception& e) { promise.reject("ERR_DOWNLOAD", e.what()); }
  });
});

// A coroutine: starts on the JS thread, runs until its first suspension, and continues wherever
// it is resumed.
def.function("read", [](std::string path) -> cpp::Task<std::string> {
  co_await cpp::background();     // off the JS thread, for the blocking part
  std::string text = readFile(path);
  co_return text;                 // JavaScript gets it, on the JS thread
});

// The same thing, without writing a coroutine.
def.function("sha256", cpp::background([](std::string text) { return sha256(text); }));
```

A coroutine takes its parameters by value, and must not touch a `js::Value` after it left the JS
thread; `co_await cpp::onJSThread(rt)` comes back. A throw — before or after a suspension —
rejects the promise.

## Errors

```cpp
throw cpp::Exception("ERR_NOT_FOUND", "no such file: " + path);   // error.code === 'ERR_NOT_FOUND'
throw std::runtime_error("failed");                               // an Error with that message
```

Any exception from an export is caught and becomes a JavaScript `Error`; an error thrown by
JavaScript you called into passes through unchanged.

## Threads and lifetimes

- Everything that touches a `js::Value` — conversions, your `define`d functions, listeners — runs on
  the runtime's JS thread. `emit` and `Promise::resolve`/`reject` are the exceptions: any thread.
- A `cpp::TypedArray` is JavaScript's memory: use it in a synchronous export and do not keep it.
- A function that a module exports captures the module, and keeps it alive. If JavaScript is the last
  to hold one after its context closed, the module is destroyed when the garbage collector finalizes
  that function - on its thread, like a shared object.
- `cpp::Context` owns the modules. `Context::close()` calls each module's `onDestroy()` once, while
  the context still reads as open and the other modules are reachable; a throw is logged.
- `object->context()` is the context an object belongs to. A shared object made by `new`, or by an
  export that returns it, has it already in its constructor; one made anywhere else is bound when it
  first reaches JavaScript, or by `context->make<T>()` when it is made off the JS thread.

## Using it

### From a host (desktop, tests, tools)

A host owns the `js::Runtime` and says how to reach its JS thread by implementing `cpp::Scheduler`:

```cpp
auto scheduler = std::make_shared<cpp::ManualScheduler>();            // the constructing thread is the JS thread
auto attached = cpp::AttachedRuntime::attach(*runtime, cpp::Context::create(), scheduler);

runtime->evaluateJavaScript(source, "app.js");                        // expo.modules.MathModule.add(1, 2)
scheduler->drain();                                                   // run what other threads posted
runtime->drainMicrotasks();
```

Destroy the attachment on the JS thread, before the runtime. `Context::create(false)` leaves out the
`EXPO_MODULE` ones; `context->emplace<T>("Name", args...)` adds a module by hand. If `globalThis.expo`
exists — `expo-modules-core` installs it — the modules join it, and the host's own modules win a
name both have.

### From the Android bridge

`libexpo-modules-v2` links this library and serves C++ modules next to Kotlin ones under
`expo.modules`. Each runtime gets a context of its own, so a module has one instance per runtime,
and a Kotlin module wins a name both define.

An app compiles `libexpo-modules-v2` from the C++ sources this project publishes (the `cpp`
classifier of `expo-modules-v2-react`), so its own modules go into that same library: add their
source files to the `expo-modules-v2` target, and they can include `<expo-modules-cpp/Expo.h>`.

`EXPO_MODULE` registers a module as the library loads. Load the library from Kotlin
(`ExpoModulesV2.load()` does) before JavaScript asks for the module; a context looks a module up
when it is first used, so a library loaded later is found too.

Two things to know:

- The C++ API adds code to the library whether or not an app has a C++ module. A build that has none
  can leave it out with `-DEXPO_MODULES_V2_CPP_API=OFF`, which restores the library to the size it had
  without it.
  Measured on x86-64 with `-Os`, it adds about 100 KB stripped (322 KB to 425 KB); the report on a
  pull request gives the number for arm64.
- A module library other than the one the app builds can use the API only if it links a copy of the
  bridge that exports all of it, which the desktop build does (`EXPO_MODULES_V2_EXPORT_CPP_API`) and
  the Android build does not: the symbols of a static library stay local to the library.

To reach the JS thread from your own thread, use `cpp::runOnJSThread(runtime, job)` with a
`js::Runtime&` you were handed by a call. Its job runs there, at once if you already are:

```cpp
cpp::runOnJSThread(rt, [callback](js::Runtime& rt) { callback->call(rt, 42); });
```

## Kotlin and C++ side by side

| Kotlin | C++ |
| --- | --- |
| `@ExpoModule class M : Module()` | `class M : cpp::Module` + `EXPO_MODULE(M)`, or `cpp::ExpoModule<M>` |
| `@JS fun f(...)` | `def.function("f", ...)`, or `EXPO_JS(f)` |
| `@JS val`, `@JS var` | `def.property(...)`, or `EXPO_JS(x)` |
| `@Constant` | `def.constant(...)` with a getter, or `EXPO_JS_CONSTANT(x)` |
| `@JS suspend fun` | a function returning `cpp::Task<T>` |
| `Promise<T>` | `cpp::Promise<T>` |
| `@Event val onX = event<T>(...)` | `cpp::Event<T> onX{this, "x", hooks}`, or `EXPO_EVENT(onX, T);` |
| `@ExpoSharedObject class` | `cpp::SharedObject` + `static define`, or `cpp::ExpoSharedObject<S>` |
| `SharedRef<T>` | `cpp::SharedRef<T>` |
| `@Record data class` | a struct + `EXPO_RECORD` |
| `Enumerable` enum | an enum + `EXPO_ENUM` |
| `TypedArray` | `cpp::TypedArray` |
| `JavaScriptValue`, `JavaScriptObject` | `js::Value`, `js::Object` |
| `JavaScriptThrowable(code)` | `cpp::Exception(code, message)` |
| `ExpoContext`, `context.module<T>()` | `cpp::Context`, `context->module<T>()` |
| `Module.onDestroy()` | `Module::onDestroy()` |
| `SharedObject.sharedObjectDidRelease()` | `SharedObject::didRelease()` |
| `@BufferMode` | not needed: nothing crosses a JNI boundary |

## Performance

A call costs what a hand-written host function costs. Measured on Hermes, Release build, one call
from a JavaScript loop (`api/src/test/cpp/benchmark`):

| Call | Hand-written JSI | C++ API |
| --- | --- | --- |
| no arguments, no result | 9 ns | 12 ns |
| `add(int, int)` | 23 ns | 22 ns |
| `concat(string, string)` | 61 ns | 66 ns |
| `sum(array of 100 numbers)` | 1026 ns | 1093 ns |
| record in, record out | 226 ns | 122 ns |

What keeps it there: arity, the kind of every parameter and the argument conversions are resolved
at compile time per export, so past JSI's own host function a call is an indirect jump and
straight-line code, with no allocation of its own; errors are built out of line. The record row beats the
hand-written one because a record's property names are interned once per runtime instead of
looked up on every call.

## Tests

The suite runs real JavaScript on a real Hermes, with no JVM:

```sh
cmake -S api/src/test/cpp -B build-native/cpp-tests -G Ninja \
  -DJSI_DIR=$PWD/api/build/hermes-env-jsi \
  -DHERMES_ENV_LIBS_DIR=$PWD/api/build/hermes-env-libs \
  -DHERMES_API_DIR=$PWD/../hermes-tests-environment/third-party/hermes
cmake --build build-native/cpp-tests
ctest --test-dir build-native/cpp-tests --output-on-failure
```

`-DEXPO_MODULES_CPP_SANITIZE=ON` builds it with AddressSanitizer and UBSan. The library also builds
warning-free with clang and libc++, which is what the NDK ships.
