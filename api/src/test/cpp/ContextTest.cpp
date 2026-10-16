#include "Fixture.h"

#include <atomic>

namespace {
  class Peer : public cpp::Module {
  public:
    std::string name() const { return "peer"; }
  };

  class Owner : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("hit", [this] { return ++hits; });
      def.function("peerName", [this] {
        const auto peer = context()->module<Peer>();
        return peer == nullptr ? std::string("none") : peer->name();
      });
    }

    void onDestroy() override {
      destroyed++;
      contextWasOpenOnDestroy = contextOrNull() != nullptr;
      peerReachableOnDestroy = context()->module<Peer>() != nullptr;
    }

    int hits = 0;
    int destroyed = 0;
    bool contextWasOpenOnDestroy = false;
    bool peerReachableOnDestroy = false;
  };

  class Unrelated : public cpp::Module {};

  class FailingToLetGo : public cpp::Module {
  public:
    void onDestroy() override { throw std::runtime_error("cannot let go"); }
  };

  class Recursive : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition&) override { (void) context()->module<Recursive>(); }
  };
} // namespace

EXPO_MODULE(Recursive, "RecursiveStatic")

TEST(context_a_context_owns_its_modules_and_binds_each_one_when_it_is_added) {
  auto context = cpp::Context::create(false);
  auto owner = std::make_shared<Owner>();
  CHECK(owner->contextOrNull() == nullptr);
  context->add("Owner", owner);
  CHECK(owner->contextOrNull() == context);
  CHECK(context->module<Owner>() == owner);
  CHECK(context->module("Owner") == owner);
  CHECK(context->module<Unrelated>() == nullptr);
  CHECK(context->module("Missing") == nullptr);
}

TEST(context_an_unbound_object_says_so) {
  auto owner = std::make_shared<Owner>();
  try {
    (void) owner->context();
    CHECK(false);
  } catch (const std::logic_error& error) {
    CHECK_CONTAINS(error.what(), "not bound to a cpp::Context yet");
  }
}

TEST(context_runtimes_that_share_a_context_serve_its_modules_and_closing_them_does_not_destroy_the_modules) {
  auto context = cpp::Context::create(false);
  auto owner = context->emplace<Owner>("Owner");

  {
    JsEnv first(context);
    CHECK_EQ(first.eval("expo.modules.Owner.hit()"), "1");
  }
  {
    JsEnv second(context);
    CHECK_EQ(second.eval("expo.modules.Owner.hit()"), "2");
  }
  CHECK_EQ(owner->destroyed, 0);

  context->close();
  CHECK_EQ(owner->destroyed, 1);
}

TEST(context_a_module_reaches_another_module_of_its_context) {
  JsEnv env;
  env.context().emplace<Owner>("Owner");
  CHECK_EQ(env.eval("expo.modules.Owner.peerName()"), "none");
  env.context().emplace<Peer>("Peer");
  CHECK_EQ(env.eval("expo.modules.Owner.peerName()"), "peer");
}

TEST(context_a_module_is_destroyed_once_when_its_context_closes_while_the_context_still_reads_open) {
  auto context = cpp::Context::create(false);
  auto owner = context->emplace<Owner>("Owner");
  context->emplace<Peer>("Peer");
  CHECK_EQ(owner->destroyed, 0);

  context->close();
  CHECK_EQ(owner->destroyed, 1);
  CHECK(owner->contextWasOpenOnDestroy);
  CHECK(owner->peerReachableOnDestroy);  // the others are still there while one is destroyed
}

TEST(context_closing_a_context_twice_destroys_its_modules_once) {
  auto context = cpp::Context::create(false);
  auto owner = context->emplace<Owner>("Owner");
  context->close();
  context->close();
  CHECK_EQ(owner->destroyed, 1);
  CHECK(context->isClosed());
  CHECK(context->module<Owner>() == nullptr);
  CHECK(context->moduleNames().empty());
  CHECK_THROWS(context->emplace<Peer>("Late"));
}

TEST(context_a_module_that_fails_to_let_go_is_logged_and_does_not_stop_the_others) {
  auto context = cpp::Context::create(false);
  context->emplace<FailingToLetGo>("Failing");
  auto owner = context->emplace<Owner>("Owner");
  context->close();
  CHECK_EQ(owner->destroyed, 1);
  CHECK(context->isClosed());
}

TEST(context_an_object_of_a_closed_context_reports_that_context_as_gone) {
  auto context = cpp::Context::create(false);
  auto owner = context->emplace<Owner>("Owner");
  context->close();
  CHECK(owner->contextOrNull() == nullptr);
  try {
    (void) owner->context();
    CHECK(false);
  } catch (const std::logic_error& error) {
    CHECK_CONTAINS(error.what(), "is closed");
  }
}

TEST(context_a_module_that_outlives_its_context_is_rebound_to_the_next_one) {
  auto first = cpp::Context::create(false);
  auto owner = first->emplace<Owner>("Owner");
  first->close();

  auto second = cpp::Context::create(false);
  second->add("Owner", owner);
  CHECK(owner->contextOrNull() == second);
}

TEST(context_an_object_of_a_live_context_cannot_join_a_different_one) {
  auto first = cpp::Context::create(false);
  auto second = cpp::Context::create(false);
  auto owner = first->emplace<Owner>("Owner");
  try {
    second->add("Owner", owner);
    CHECK(false);
  } catch (const std::logic_error& error) {
    CHECK_CONTAINS(error.what(), "already belongs to another cpp::Context");
  }
}

TEST(context_a_shared_object_made_off_the_js_thread_is_bound_by_the_context_that_makes_it) {
  class Item : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Item>& cls) { cls.constructor<>(); }
  };

  auto context = cpp::Context::create(false);
  auto item = context->make<Item>();
  CHECK(item->contextOrNull() == context);
}

TEST(context_a_module_that_asks_for_itself_while_it_defines_itself_is_reported) {
  auto context = cpp::Context::create();
  try {
    (void) context->module("RecursiveStatic");
    CHECK(false);
  } catch (const std::logic_error& error) {
    CHECK_CONTAINS(error.what(), "asked for itself while it was being defined");
  }
  // And nothing half-made is left behind.
  CHECK_THROWS(context->module("RecursiveStatic"));
}

TEST(context_adding_a_module_twice_under_one_name_is_an_error) {
  auto context = cpp::Context::create(false);
  context->emplace<Peer>("Same");
  CHECK_THROWS(context->emplace<Unrelated>("Same"));
  CHECK_EQ(context->moduleNames().size(), size_t(1));
}

TEST(context_a_define_that_throws_leaves_the_module_unregistered) {
  class Broken : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition&) override { throw std::runtime_error("cannot define"); }
  };

  auto context = cpp::Context::create(false);
  CHECK_THROWS(context->emplace<Broken>("Broken"));
  CHECK(context->module("Broken") == nullptr);
}

// --- modules registered with EXPO_MODULE ---------------------------------------------------------

namespace {
  std::atomic<int> gStaticCreated{0};

  class StaticGreeter : public cpp::Module {
  public:
    StaticGreeter() { gStaticCreated++; }

    void define(cpp::ModuleDefinition& def) override {
      def.function("greet", [](std::string name) { return "Hello, " + name + "!"; });
    }
  };

  class StaticRenamed : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override { def.function("which", [] { return std::string("renamed"); }); }
  };
} // namespace

EXPO_MODULE(StaticGreeter)
EXPO_MODULE(StaticRenamed, "Renamed")

TEST(static_module_is_served_by_a_context_that_includes_static_modules) {
  auto context = cpp::Context::create();
  JsEnv env(context);
  CHECK_EQ(env.eval("expo.modules.StaticGreeter.greet('Expo')"), "Hello, Expo!");
  CHECK_EQ(env.eval("expo.modules.Renamed.which()"), "renamed");
  CHECK_EQ(env.eval("typeof expo.modules.StaticRenamed"), "undefined");
}

TEST(static_module_is_created_on_first_use_not_when_the_context_is_made) {
  const int before = gStaticCreated.load();
  auto context = cpp::Context::create();
  CHECK_EQ(gStaticCreated.load(), before);

  CHECK(context->module<StaticGreeter>() != nullptr);
  CHECK_EQ(gStaticCreated.load(), before + 1);
  CHECK(context->module("StaticGreeter") == context->module<StaticGreeter>());
  CHECK_EQ(gStaticCreated.load(), before + 1);  // one instance per context
}

TEST(static_module_each_context_has_its_own_instance) {
  auto first = cpp::Context::create();
  auto second = cpp::Context::create();
  CHECK(first->module<StaticGreeter>() != second->module<StaticGreeter>());
}

TEST(static_module_a_context_can_leave_static_modules_out) {
  auto context = cpp::Context::create(false);
  CHECK(context->module<StaticGreeter>() == nullptr);
  CHECK(context->module("StaticGreeter") == nullptr);
}

TEST(static_module_the_namespace_lists_static_modules_and_added_ones_once_each) {
  auto context = cpp::Context::create();
  context->emplace<Peer>("Peer");
  (void) context->module<StaticGreeter>();  // made, and so listed once, not twice
  JsEnv env(context);
  const std::string names = env.eval("Object.keys(expo.modules).sort().join()");
  CHECK_CONTAINS(names, "Peer");
  CHECK_CONTAINS(names, "StaticGreeter");
  CHECK_CONTAINS(names, "Renamed");
  CHECK_EQ(env.eval("Object.keys(expo.modules).filter((n) => n === 'StaticGreeter').length"), "1");
}

TEST(static_module_is_destroyed_with_its_context) {
  class Tracked : public cpp::Module {
  public:
    void onDestroy() override { destroyed = true; }

    bool destroyed = false;
  };

  auto context = cpp::Context::create(false);
  auto tracked = context->emplace<Tracked>("Tracked");
  context->close();
  CHECK(tracked->destroyed);
}

// --- a context in the constructor ----------------------------------------------------------------

namespace {
  /** Reads its context in its constructor, as Kotlin's `SharedObject()` can. */
  class Needy : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Needy>& cls) {
      cls.constructor<>();
      cls.function("bound", [](Needy& self) { return self.boundInConstructor; });
      cls.function("peer", [](Needy& self) { return self.peerInConstructor; });
    }

    Needy() {
      const std::shared_ptr<cpp::Context> context = contextOrNull();
      boundInConstructor = context != nullptr;
      peerInConstructor = context != nullptr && context->module<Peer>() != nullptr ? "peer" : "none";
    }

    bool boundInConstructor = false;
    std::string peerInConstructor;
  };

  class Maker : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("make", [] { return std::make_shared<Needy>(); });
      def.function("count", [] { return 1; });
      def.sharedClass<Needy>();
    }
  };
} // namespace

TEST(context_an_object_built_by_new_has_its_context_in_its_constructor) {
  JsEnv env;
  env.context().emplace<Maker>("Maker");
  env.context().emplace<Peer>("Peer");
  CHECK_EQ(env.eval("const n = new expo.modules.Maker.Needy(); [n.bound(), n.peer()].join()"), "true,peer");
}

TEST(context_an_object_built_by_an_export_has_its_context_in_its_constructor) {
  JsEnv env;
  env.context().emplace<Maker>("Maker");
  CHECK_EQ(env.eval("expo.modules.Maker.make().bound()"), "true");
}

TEST(context_an_object_built_natively_has_none_until_it_reaches_javascript) {
  JsEnv env;
  env.context().emplace<Maker>("Maker");
  auto needy = std::make_shared<Needy>();
  CHECK(!needy->boundInConstructor);
  CHECK(needy->contextOrNull() == nullptr);
}

TEST(context_an_object_a_context_makes_is_bound_in_its_constructor) {
  auto context = cpp::Context::create(false);
  auto needy = context->make<Needy>();
  CHECK(needy->boundInConstructor);
  CHECK(needy->contextOrNull() == context);
}

TEST(context_a_module_a_context_makes_belongs_to_it_even_inside_a_call_of_another) {
  class Intruder : public cpp::Module {
  public:
    explicit Intruder(std::shared_ptr<cpp::Context> other) : other_(std::move(other)) {
    }

    void define(cpp::ModuleDefinition& def) override {
      // A call that constructs a module of a different context.
      def.function("build", [this]() -> std::shared_ptr<Needy> {
        other_->emplace<Peer>("Made");
        return std::make_shared<Needy>();
      });
    }

    std::shared_ptr<cpp::Context> other_;
  };

  auto other = cpp::Context::create(false);
  JsEnv env;
  env.context().emplace<Intruder>("Intruder", other);
  env.exec("expo.modules.Intruder.build()");
  CHECK(other->module("Made") != nullptr);
  CHECK(other->module("Made")->contextOrNull() == other);
}

namespace {
  class Restless : public cpp::Module {
  public:
    void onDestroy() override {
      try {
        context()->emplace<Peer>("Late");
        added = true;
      } catch (const std::logic_error&) {
        added = false;
      }
    }

    bool added = true;
  };
} // namespace

TEST(context_a_module_cannot_be_added_while_the_context_is_closing) {
  // Closing destroys the modules there are; one added in the middle would be dropped without its
  // onDestroy ever running.
  auto context = cpp::Context::create(false);
  auto restless = context->emplace<Restless>("Restless");
  context->close();

  CHECK(!restless->added);
  CHECK(context->module("Late") == nullptr);
}
