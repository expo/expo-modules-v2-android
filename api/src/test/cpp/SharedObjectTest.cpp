#include "Fixture.h"

#include <atomic>

namespace {
  struct Origin {
    int x = 0;
    int y = 0;
  };
  EXPO_RECORD(Origin, x, y)

  std::atomic<int> gCounterDestroyed{0};

  class Counter : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Counter>& cls) {
      cls.constructor<int>();
      cls.function("increment", &Counter::increment);
      cls.function("add", [](Counter& self, int amount) { return self.count_ += amount; });
      cls.function("compare", [](const Counter& self, const Counter& other) { return self.count_ - other.count_; });
      cls.function("byRef", [](const std::shared_ptr<Counter>& self, int amount) {
        // Lives on past the call, as a `std::shared_ptr` parameter lets it.
        kept = self;
        return self->count_ += amount;
      });
      cls.property("value", &Counter::count_);
    }

    inline static std::shared_ptr<Counter> kept;

    explicit Counter(int start = 0) : count_(start) {
    }

    ~Counter() override { gCounterDestroyed++; }

    void didRelease() override { releaseCount++; }

    int increment() { return ++count_; }

    int count_;
    int releaseCount = 0;
  };

  /** A second class, so a declared parameter type can be shown to reject the wrong one. */
  class Tag : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Tag>& cls) {
      cls.constructor<>();
      cls.function("label", [](Tag&) { return std::string("tag"); });
    }
  };

  /** Constructible only natively: JavaScript gets instances but no class to call `new` on. */
  class Hidden : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Hidden>& cls) {
      cls.function("name", [](Hidden&) { return std::string("hidden"); });
    }
  };

  class Greeter : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Greeter>& cls) {
      cls.constructor<std::string>();
      cls.function("greet", [](Greeter& self, std::string name) { return self.text_ + ", " + name + "!"; });
      cls.property("greeting", [](Greeter& self) { return self.text_; });
    }

    explicit Greeter(std::string text) : text_(std::move(text)) {
    }

    std::string text_;
  };

  class Badge : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Badge>& cls) {
      cls.name("Renamed");
      cls.constructor<int>();
      cls.function("value", [](Badge& self) { return self.n_; });
    }

    explicit Badge(int n) : n_(n) {
    }

    int n_;
  };

  class Marker : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Marker>& cls) {
      cls.constructor<int, Origin, std::vector<std::string>>();
      cls.function("describe", [](Marker& self) {
        std::string tags;
        for (const std::string& tag: self.tags_) {
          tags += (tags.empty() ? "" : "|") + tag;
        }
        return std::to_string(self.count_) + "@" + std::to_string(self.origin_.x) + "," + std::to_string(self.origin_.y) + ":" + tags;
      });
    }

    Marker(int count, Origin origin, std::vector<std::string> tags)
      : count_(count), origin_(origin), tags_(std::move(tags)) {
    }

    int count_;
    Origin origin_;
    std::vector<std::string> tags_;
  };

  class Window : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Window>& cls) {
      cls.constructor<std::chrono::milliseconds>();
      cls.function("millis", [](Window& self) { return static_cast<int>(self.span_.count()); });
    }

    explicit Window(std::chrono::milliseconds span) : span_(span) {
    }

    std::chrono::milliseconds span_;
  };

  class Connection : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Connection>& cls) {
      cls.constructor<std::string, std::optional<std::map<std::string, int>>, std::optional<std::vector<uint8_t>>>();
      cls.function("describe", [](Connection& self) {
        return self.path_ + "/" + (self.options_ ? std::to_string(self.options_->size()) : "null") + "/" +
               (self.data_ ? std::to_string(self.data_->size()) : "null");
      });
    }

    Connection(std::string path, std::optional<std::map<std::string, int>> options, std::optional<std::vector<uint8_t>> data)
      : path_(std::move(path)), options_(std::move(options)), data_(std::move(data)) {
    }

    std::string path_;
    std::optional<std::map<std::string, int>> options_;
    std::optional<std::vector<uint8_t>> data_;
  };

  /** A `SharedRef`: an opaque handle to a native value. */
  class TextRef : public cpp::SharedRef<std::string> {
  public:
    static void define(cpp::ClassDefinition<TextRef>& cls) {
      cls.function("length", [](TextRef& self) { return static_cast<int>(self.ref().size()); });
    }

    using SharedRef::SharedRef;
  };

  class NumberRef : public cpp::SharedRef<std::atomic<int>> {
  public:
    static void define(cpp::ClassDefinition<NumberRef>&) {
    }

    NumberRef() : SharedRef(7) {
    }
  };

  class Animal : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Animal>& cls) {
      cls.constructor<std::string>();
      cls.function("name", [](Animal& self) { return self.name_; });
      cls.function("speak", [](Animal&) { return std::string("..."); });
    }

    explicit Animal(std::string name) : name_(std::move(name)) {
    }

    std::string name_;
  };

  class Dog : public Animal {
  public:
    static void define(cpp::ClassDefinition<Dog>& cls) {
      cls.extends<Animal>();
      cls.constructor<std::string>();
      cls.function("fetch", [](Dog&) { return std::string("fetching"); });
    }

    using Animal::Animal;
  };

  class Pool : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Pool>& cls) {
      cls.constructor<>();
      cls.function("size", [](Pool&) { return 4; });
    }

    size_t externalMemoryPressure() const override { return 1 << 20; }
  };

  class Owner;

  class Probe : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Probe>& cls);
  };

  class Owner : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override { def.sharedClass<Probe>(); }

    int hit() { return ++hits; }

    int hits = 0;
  };

  void Probe::define(cpp::ClassDefinition<Probe>& cls) {
    cls.constructor<>();
    // What a shared object reaches through its context: the module that owns its state.
    cls.function("ownerHits", [](Probe& self) { return self.context()->module<Owner>()->hit(); });
  }

  class Counters : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("create", [this] { return last = std::make_shared<Counter>(); });
      def.function("current", [this] { return last; });
      def.function("bumpTwice", [](Counter& counter) {
        counter.increment();
        return counter.increment();
      });
      def.function("bumpPtr", [](Counter* counter) { return counter == nullptr ? -1 : counter->increment(); });
      def.function("bumpShared", [](std::shared_ptr<Counter> counter) { return counter == nullptr ? -1 : counter->increment(); });
      def.function("tag", [] { return std::make_shared<Tag>(); });
      def.function("hidden", [] { return std::make_shared<Hidden>(); });
      def.function("remember", [this](std::shared_ptr<Greeter> greeter) {
        remembered = greeter;
        return greeter->text_;
      });
      def.function("recall", [this] { return remembered; });
      def.function("text", [](std::string value) { return std::make_shared<TextRef>(std::move(value)); });
      def.function("number", [] { return std::make_shared<NumberRef>(); });
      def.function("readText", [](TextRef& ref) { return ref.ref(); });
      def.function("animal", [] { return std::make_shared<Animal>("generic"); });
      def.function("dog", [] { return std::make_shared<Dog>("rex"); });
      def.function("nameOf", [](Animal& animal) { return animal.name_; });
      def.function("dogName", [](Dog& dog) { return dog.name_; });
      def.function("describeMarker", [](Marker& marker) { return marker.count_; });
      def.function("pool", [] { return std::make_shared<Pool>(); });
      def.function("nothing", []() -> std::shared_ptr<Counter> { return nullptr; });
      def.sharedClass<Counter>();
      def.sharedClass<Tag>();
      def.sharedClass<Hidden>();
      def.sharedClass<Greeter>();
      def.sharedClass<Badge>();
      def.sharedClass<Marker>();
      def.sharedClass<Window>();
      def.sharedClass<Connection>();
      def.sharedClass<Animal>();
      def.sharedClass<Dog>();
      def.sharedClass<Pool>();
    }

    std::shared_ptr<Counter> last;
    std::shared_ptr<Greeter> remembered;
  };

  struct Env : JsEnv {
    std::shared_ptr<Counters> counters;

    Env() { counters = context().emplace<Counters>("Counters"); }
  };
} // namespace

TEST(shared_object_a_module_can_return_a_shared_object_and_javascript_can_call_its_members) {
  Env env;
  CHECK_EQ(env.eval("globalThis.c = expo.modules.Counters.create(); c.increment()"), "1");
  CHECK_EQ(env.eval("c.increment()"), "2");
  CHECK_EQ(env.eval("c.value"), "2");

  env.exec("c.value = 40");
  CHECK_EQ(env.eval("c.increment()"), "41");
  CHECK_EQ(env.counters->last->count_, 41);
}

TEST(shared_object_a_member_defined_as_a_lambda_receives_the_object) {
  Env env;
  CHECK_EQ(env.eval("globalThis.c = expo.modules.Counters.create(); c.add(5)"), "5");
  CHECK_EQ(env.eval("c.add(2)"), "7");
}

TEST(shared_object_the_same_native_object_reads_as_the_same_javascript_object) {
  Env env;
  CHECK_EQ(env.eval("globalThis.a = expo.modules.Counters.create(); a === expo.modules.Counters.current()"), "true");
}

TEST(shared_object_a_shared_object_crosses_back_as_the_instance_it_came_from) {
  Env env;
  CHECK_EQ(env.eval("globalThis.a = expo.modules.Counters.create(); expo.modules.Counters.bumpTwice(a)"), "2");
  // The same instance on both sides: the count carried over rather than starting again.
  CHECK_EQ(env.eval("a.increment()"), "3");
  CHECK_EQ(env.counters->last->count_, 3);
}

TEST(shared_object_a_parameter_may_be_a_reference_a_pointer_or_a_shared_ptr) {
  Env env;
  env.exec("globalThis.a = expo.modules.Counters.create();");
  CHECK_EQ(env.eval("expo.modules.Counters.bumpTwice(a)"), "2");
  CHECK_EQ(env.eval("expo.modules.Counters.bumpPtr(a)"), "3");
  CHECK_EQ(env.eval("expo.modules.Counters.bumpShared(a)"), "4");
}

TEST(shared_object_a_pointer_or_shared_ptr_parameter_takes_null_and_a_reference_does_not) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Counters.bumpPtr(null)"), "-1");
  CHECK_EQ(env.eval("expo.modules.Counters.bumpPtr()"), "-1");
  CHECK_EQ(env.eval("expo.modules.Counters.bumpShared(null)"), "-1");
  CHECK_CONTAINS(env.errorOf("expo.modules.Counters.bumpTwice(null)"), "expected a shared object, got null");
}

TEST(shared_object_a_result_that_is_a_null_pointer_is_null) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Counters.nothing()"), "null");
}

TEST(shared_object_a_shared_object_of_the_wrong_class_is_rejected_at_the_boundary) {
  Env env;
  const std::string message = env.errorOf("expo.modules.Counters.bumpTwice(expo.modules.Counters.tag())");
  CHECK_CONTAINS(message, "expected Counter");
  CHECK_CONTAINS(message, "got Tag");
}

TEST(shared_object_a_plain_object_is_not_accepted_where_a_shared_object_is_declared) {
  Env env;
  CHECK_CONTAINS(env.errorOf("expo.modules.Counters.bumpTwice({})"), "not a shared object");
  CHECK_CONTAINS(env.errorOf("expo.modules.Counters.bumpTwice(5)"), "expected Counter, got a number");
  CHECK_CONTAINS(env.errorOf("expo.modules.Counters.bumpTwice(expo.modules.Counters)"), "not a shared object");
}

TEST(shared_object_first_instances_of_two_classes_do_not_alias) {
  // Ids are process-wide, not per class: the first Counter and the first Tag must never share a
  // key in the tables, or the Tag would come back wearing the Counter's facade.
  Env env;
  env.exec("globalThis.c = expo.modules.Counters.create(); globalThis.t = expo.modules.Counters.tag();");
  CHECK_EQ(env.eval("c === t"), "false");
  CHECK_EQ(env.eval("t.label()"), "tag");
  CHECK_EQ(env.eval("c.increment()"), "1");
  CHECK_EQ(env.eval("typeof t.increment"), "undefined");
}

TEST(shared_object_native_code_finds_the_javascript_object_of_an_instance_it_handed_over) {
  Env env;
  env.exec("globalThis.c = expo.modules.Counters.create();");
  const std::shared_ptr<Counter> counter = env.counters->last;

  // Native -> JS: the facade JavaScript already holds, not a second one.
  const js::Value facade = env.attached().objectOf(*counter);
  CHECK(facade.isObject());
  env.rt().global().setProperty(env.rt(), "fromNative", js::Value(env.rt(), facade));
  CHECK_EQ(env.eval("fromNative === c"), "true");

  // An instance that never crossed has no facade here, and asking does not create one.
  Counter fresh;
  CHECK(env.attached().objectOf(fresh).isUndefined());
  CHECK(env.attached().objectOf(fresh).isUndefined());
}

TEST(shared_object_release_runs_the_hook_exactly_once_and_rejects_later_calls) {
  Env env;
  env.exec("globalThis.c = expo.modules.Counters.create(); c.increment();");
  CHECK_EQ(env.counters->last->releaseCount, 0);

  env.exec("c.release(); c.release();");
  CHECK_EQ(env.counters->last->releaseCount, 1);
  CHECK(env.counters->last->isReleased());

  CHECK_EQ(env.errorOf("c.increment()"), "Cannot access 'increment' on a released Counter");
  CHECK_CONTAINS(env.errorOf("c.value"), "released");
}

TEST(shared_object_native_code_can_release_an_object_too) {
  Env env;
  env.exec("globalThis.c = expo.modules.Counters.create();");
  env.counters->last->release();
  env.counters->last->release();
  CHECK_EQ(env.counters->last->releaseCount, 1);
  CHECK_CONTAINS(env.errorOf("c.increment()"), "released");
}

TEST(shared_object_a_released_object_is_refused_as_an_argument) {
  Env env;
  env.exec("globalThis.c = expo.modules.Counters.create(); c.release();");
  const std::string message = env.errorOf("expo.modules.Counters.bumpTwice(c)");
  CHECK_CONTAINS(message, "Counter");
  CHECK_CONTAINS(message, "released");
}

TEST(shared_object_a_throwing_release_hook_is_logged_and_does_not_break_the_release) {
  class Brittle : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Brittle>& cls) { cls.constructor<>(); }

    void didRelease() override { throw std::runtime_error("cannot let go"); }
  };
  class Holder : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override { def.sharedClass<Brittle>(); }
  };

  JsEnv env;
  env.context().emplace<Holder>("Holder");
  env.exec("globalThis.b = new expo.modules.Holder.Brittle(); b.release();");
  CHECK_CONTAINS(env.errorOf("b.addListener('x', () => {})"), "released");
}

TEST(shared_object_the_object_id_is_exposed_to_javascript) {
  Env env;
  env.exec("globalThis.c = expo.modules.Counters.create();");
  CHECK_EQ(env.eval("c.__expoSharedObjectId"), std::to_string(env.counters->last->objectId()));
  CHECK_EQ(env.eval("Object.keys(c).includes('__expoSharedObjectId')"), "false");
}

TEST(shared_object_javascript_can_construct_an_instance) {
  Env env;
  CHECK_EQ(env.eval("globalThis.g = new expo.modules.Counters.Greeter('Hello'); g.greet('World')"), "Hello, World!");
  CHECK_EQ(env.eval("g.greeting"), "Hello");
  CHECK_EQ(env.eval("g instanceof expo.modules.Counters.Greeter"), "true");
  CHECK_EQ(env.eval("Object.getPrototypeOf(g) === expo.modules.Counters.Greeter.prototype"), "true");
  CHECK_EQ(env.eval("expo.modules.Counters.Greeter.prototype.constructor === expo.modules.Counters.Greeter"), "true");
  CHECK_EQ(env.eval("expo.modules.Counters.Greeter.name + '/' + expo.modules.Counters.Greeter.length"), "Greeter/1");
}

TEST(shared_object_a_constructor_argument_is_checked) {
  Env env;
  CHECK_EQ(
    env.errorOf("new expo.modules.Counters.Greeter(5)"),
    "Invalid argument 1 of 'Greeter': expected a string, got a number"
  );
  CHECK_CONTAINS(env.errorOf("new expo.modules.Counters.Greeter()"), "Greeter expects 1 argument(s), but received 0");
}

TEST(shared_object_a_constructor_takes_records_lists_and_durations) {
  Env env;
  CHECK_EQ(
    env.eval("new expo.modules.Counters.Marker(3, { x: 1, y: 2 }, ['a', 'b']).describe()"),
    "3@1,2:a|b"
  );
  CHECK_EQ(env.eval("new expo.modules.Counters.Window(1.5).millis()"), "1500");
}

TEST(shared_object_a_constructors_trailing_optional_arguments_can_be_left_out) {
  Env env;
  CHECK_EQ(env.eval("new expo.modules.Counters.Connection('main').describe()"), "main/null/null");
  CHECK_EQ(env.eval("new expo.modules.Counters.Connection('main', { a: 1 }).describe()"), "main/1/null");
  CHECK_EQ(env.eval("new expo.modules.Counters.Connection('main', null, new Uint8Array(3).buffer).describe()"), "main/null/3");
}

TEST(shared_object_a_class_without_a_constructor_is_not_exposed_but_its_instances_are) {
  Env env;
  CHECK_EQ(env.eval("typeof expo.modules.Counters.Hidden"), "undefined");
  CHECK_EQ(env.eval("expo.modules.Counters.hidden().name()"), "hidden");
}

TEST(shared_object_a_class_is_exported_under_the_name_it_declares_or_the_one_it_is_given) {
  class Names : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.sharedClass<Badge>();
      def.sharedClass<Tag>("AliasedTag");
    }
  };

  JsEnv env;
  env.context().emplace<Names>("Names");
  CHECK_EQ(env.eval("new expo.modules.Names.Renamed(4).value()"), "4");
  CHECK_EQ(env.eval("new expo.modules.Names.AliasedTag().label()"), "tag");
  CHECK_EQ(env.eval("typeof expo.modules.Names.Tag"), "undefined");
}

TEST(shared_object_a_shared_object_stored_by_a_module_comes_back_as_the_same_object) {
  Env env;
  CHECK_EQ(env.eval("globalThis.g = new expo.modules.Counters.Greeter('hi'); expo.modules.Counters.remember(g)"), "hi");
  CHECK_EQ(env.eval("expo.modules.Counters.recall() === g"), "true");
}

TEST(shared_object_a_shared_ref_exposes_the_type_it_wraps_and_nothing_else) {
  Env env;
  CHECK_EQ(env.eval("globalThis.t = expo.modules.Counters.text('hello'); t.length()"), "5");
  CHECK_EQ(env.eval("t.nativeRefType"), "string");
  CHECK_EQ(env.eval("expo.modules.Counters.readText(t)"), "hello");
  CHECK_EQ(env.eval("expo.modules.Counters.number().nativeRefType"), "atomic");
  CHECK_EQ(env.eval("Object.keys(t).join()"), "");
}

TEST(shared_object_a_ref_of_another_type_is_rejected) {
  Env env;
  CHECK_CONTAINS(env.errorOf("expo.modules.Counters.readText(expo.modules.Counters.number())"), "expected TextRef, got NumberRef");
}

TEST(shared_object_a_subclass_instance_is_accepted_where_its_base_is_declared) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Counters.nameOf(expo.modules.Counters.dog())"), "rex");
  CHECK_EQ(env.eval("expo.modules.Counters.nameOf(expo.modules.Counters.animal())"), "generic");
  CHECK_CONTAINS(env.errorOf("expo.modules.Counters.dogName(expo.modules.Counters.animal())"), "expected Dog, got Animal");
}

TEST(shared_object_a_subclass_inherits_the_members_of_its_base) {
  Env env;
  CHECK_EQ(env.eval("globalThis.d = new expo.modules.Counters.Dog('fido'); [d.name(), d.fetch(), d.speak()].join()"), "fido,fetching,...");
  CHECK_EQ(env.eval("d instanceof expo.modules.Counters.Animal && d instanceof expo.modules.Counters.Dog"), "true");
  CHECK_EQ(env.eval("expo.modules.Counters.dog() instanceof expo.modules.Counters.Animal"), "true");
}

TEST(shared_object_a_member_called_on_the_wrong_kind_of_this_is_rejected) {
  Env env;
  env.exec("globalThis.c = expo.modules.Counters.create();");
  CHECK_CONTAINS(env.errorOf("c.increment.call({})"), "'increment' called on something that is not a Counter");
  CHECK_CONTAINS(env.errorOf("c.increment.call(5)"), "'increment' called on something that is not a Counter");
  CHECK_CONTAINS(env.errorOf("c.increment.call(expo.modules.Counters.tag())"), "'increment' called on a Tag, which is not a Counter");
}

TEST(shared_object_a_member_with_two_objects_of_the_class_reads_both) {
  Env env;
  CHECK_EQ(env.eval("const a = new expo.modules.Counters.Counter(9), b = new expo.modules.Counters.Counter(4); a.compare(b)"), "5");
}

TEST(shared_object_the_memory_an_object_keeps_alive_is_reported_to_the_collector) {
  Env env;
  CHECK_EQ(env.eval("new expo.modules.Counters.Pool().size() + expo.modules.Counters.pool().size()"), "8");
}

TEST(shared_object_an_object_lives_as_long_as_javascript_or_native_code_holds_it) {
  gCounterDestroyed = 0;
  Env env;
  env.exec("expo.modules.Counters.create(); 0");
  std::weak_ptr<Counter> watcher = env.counters->last;
  env.counters->last.reset();

  // Nothing holds it natively any more; JavaScript lets go of the facade at the next collection.
  for (int pass = 0; pass < 10 && !watcher.expired(); pass++) {
    env.rt().instrumentation().collectGarbage("test");
  }
  CHECK(watcher.expired());
  CHECK_EQ(gCounterDestroyed.load(), 1);
}

TEST(shared_object_native_code_keeps_an_object_alive_after_javascript_lets_go) {
  Env env;
  env.exec("expo.modules.Counters.create(); 0");
  const std::shared_ptr<Counter> held = env.counters->last;
  for (int pass = 0; pass < 3; pass++) {
    env.rt().instrumentation().collectGarbage("test");
  }
  CHECK_EQ(held->increment(), 1);

  // And handing it back gives JavaScript a fresh facade of the same object.
  CHECK_EQ(env.eval("expo.modules.Counters.current().increment()"), "2");
}

TEST(shared_object_an_object_reaches_a_module_of_its_own_context) {
  JsEnv env;
  auto owner = env.context().emplace<Owner>("Owner");
  CHECK_EQ(
    env.eval("const probe = new expo.modules.Owner.Probe(); probe.ownerHits() + ',' + probe.ownerHits()"),
    "1,2"
  );
  CHECK_EQ(owner->hits, 2);
}

TEST(shared_object_an_object_is_bound_to_the_context_it_first_reaches_javascript_through) {
  JsEnv env;
  auto counter = std::make_shared<Counter>(1);
  CHECK(counter->contextOrNull() == nullptr);
  CHECK_THROWS(counter->context());

  class Giver : public cpp::Module {
  public:
    explicit Giver(std::shared_ptr<Counter> counter) : counter_(std::move(counter)) {
    }

    void define(cpp::ModuleDefinition& def) override { def.function("give", [this] { return counter_; }); }

  private:
    std::shared_ptr<Counter> counter_;
  };

  env.context().emplace<Giver>("Giver", counter);
  env.exec("expo.modules.Giver.give()");
  CHECK(counter->contextOrNull() != nullptr);
  CHECK(&*counter->context() == &env.context());

  // One instance cannot belong to two open contexts.
  JsEnv other;
  other.context().emplace<Giver>("Giver", counter);
  CHECK_CONTAINS(other.errorOf("expo.modules.Giver.give()"), "already belongs to another cpp::Context");
}

// --- classes that name each other, and collections of shared objects -----------------------------

namespace {
  class Chain;

  /** Each class takes and returns the other, so describing one needs the other's table. */
  class Link : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Link>& cls);

    explicit Link(int id) : id_(id) {
    }

    int id_;
  };

  class Chain : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Chain>& cls) {
      cls.constructor<>();
      cls.function("add", [](Chain& self, Link& link) {
        self.ids_.push_back(link.id_);
        return self.ids_.size();
      });
      cls.function("first", [](Chain& self) { return std::make_shared<Link>(self.ids_.at(0)); });
    }

    std::vector<int> ids_;
  };

  void Link::define(cpp::ClassDefinition<Link>& cls) {
    cls.constructor<int>();
    cls.function("chainWith", [](Link& self, Chain& chain) { return chain.ids_.size() + static_cast<size_t>(self.id_); });
    cls.function("newChain", [](Link& self) {
      auto chain = std::make_shared<Chain>();
      chain->ids_.push_back(self.id_);
      return chain;
    });
  }

  class Library : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("many", [](int n) {
        std::vector<std::shared_ptr<Link>> links;
        for (int i = 0; i < n; i++) {
          links.push_back(std::make_shared<Link>(i));
        }
        return links;
      });
      def.function("sum", [](std::vector<std::shared_ptr<Link>> links) {
        int total = 0;
        for (const auto& link: links) {
          total += link->id_;
        }
        return total;
      });
      def.function("maybe", [](bool present) -> std::optional<std::shared_ptr<Link>> {
        return present ? std::optional(std::make_shared<Link>(9)) : std::nullopt;
      });
      def.function("byName", [](std::map<std::string, std::shared_ptr<Link>> links) {
        int total = 0;
        for (const auto& [name, link]: links) {
          total += link->id_ + static_cast<int>(name.size());
        }
        return total;
      });
      def.sharedClass<Link>();
      def.sharedClass<Chain>();
    }
  };
} // namespace

TEST(shared_object_two_classes_whose_exports_name_each_other_both_register) {
  JsEnv env;
  env.context().emplace<Library>("Library");
  CHECK_EQ(env.eval("globalThis.chain = new expo.modules.Library.Chain(); chain.add(new expo.modules.Library.Link(4))"), "1");
  CHECK_EQ(env.eval("typeof chain.first().chainWith"), "function");
  CHECK_EQ(env.eval("new expo.modules.Library.Link(2).chainWith(chain)"), "3");
  CHECK_EQ(env.eval("new expo.modules.Library.Link(7).newChain().add(new expo.modules.Library.Link(1))"), "2");
}

TEST(shared_object_a_list_of_shared_objects_crosses_both_ways) {
  JsEnv env;
  env.context().emplace<Library>("Library");
  CHECK_EQ(env.eval("expo.modules.Library.many(3).length"), "3");
  CHECK_EQ(env.eval("typeof expo.modules.Library.many(2)[1].newChain"), "function");
  CHECK_EQ(env.eval("expo.modules.Library.sum(expo.modules.Library.many(5))"), "10");
  CHECK_CONTAINS(env.errorOf("expo.modules.Library.sum([{}])"), "not a shared object");
}

TEST(shared_object_an_optional_shared_object_is_null_or_the_object) {
  JsEnv env;
  env.context().emplace<Library>("Library");
  CHECK_EQ(env.eval("expo.modules.Library.maybe(false)"), "null");
  CHECK_EQ(env.eval("typeof expo.modules.Library.maybe(true).newChain"), "function");
}

TEST(shared_object_shared_objects_can_be_map_values) {
  JsEnv env;
  env.context().emplace<Library>("Library");
  CHECK_EQ(
    env.eval("expo.modules.Library.byName({ ab: new expo.modules.Library.Link(1), cde: new expo.modules.Library.Link(2) })"),
    "8"
  );
}

TEST(shared_object_runtimes_that_share_a_context_share_the_object_and_its_release) {
  auto context = cpp::Context::create(false);
  auto counters = context->emplace<Counters>("Counters");
  JsEnv first(context);
  JsEnv second(context);

  first.exec("globalThis.c = expo.modules.Counters.create(); c.increment();");
  // The very same native object in the other runtime, as a facade of that runtime's own.
  second.exec("globalThis.c = expo.modules.Counters.current();");
  CHECK_EQ(second.eval("c.increment()"), "2");
  CHECK_EQ(first.eval("c.value"), "2");
  CHECK_EQ(first.eval("c === undefined"), "false");

  // Released in one, released for both.
  first.exec("c.release();");
  CHECK_EQ(counters->last->releaseCount, 1);
  CHECK_CONTAINS(second.errorOf("c.increment()"), "released");
}

TEST(shared_object_a_receiver_may_be_a_const_reference_to_a_shared_ptr) {
  Env env;
  CHECK_EQ(env.eval("const c = new expo.modules.Counters.Counter(5); c.byRef(3) + ',' + c.value"), "8,8");
  CHECK(Counter::kept != nullptr);
  CHECK_EQ(Counter::kept->count_, 8);
  Counter::kept.reset();
}
