#include "Fixture.h"

namespace {
  class Joined : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override { def.function("answer", [] { return 42; }); }
  };

  /** Registered under the name the host's own module already has. */
  class Classic : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override { def.function("source", [] { return std::string("cpp"); }); }
  };

  /**
   * A host that owns the global namespace before the library attaches, the way `expo-modules-core`
   * owns `globalThis.expo` in a React Native app. The library has to join that namespace: the host's
   * modules and everything else on it stay reachable, and one `expo.modules` serves both.
   */
  struct Attached {
    std::unique_ptr<facebook::hermes::HermesRuntime> runtime = facebook::hermes::makeHermesRuntime();
    std::shared_ptr<cpp::ManualScheduler> scheduler = std::make_shared<cpp::ManualScheduler>();
    std::shared_ptr<cpp::Context> context = cpp::Context::create(false);
    std::unique_ptr<cpp::AttachedRuntime> attached;

    explicit Attached(const std::string& prelude) {
      runtime->evaluateJavaScript(std::make_shared<js::StringBuffer>(prelude), "prelude.js");
      context->emplace<Joined>("Joined");
      context->emplace<Classic>("Classic");
      attached = cpp::AttachedRuntime::attach(*runtime, context, scheduler);
    }

    ~Attached() {
      attached.reset();
      context->close();
    }

    std::string eval(const std::string& source) {
      return runtime->evaluateJavaScript(std::make_shared<js::StringBuffer>("String(" + source + ")"), "test.js")
                     .toString(*runtime)
                     .utf8(*runtime);
    }
  };

  /** The shape `expo-modules-core` installs: a non-writable, non-configurable global. */
  const std::string kHostNamespace = R"(
    Object.defineProperty(globalThis, 'expo', {
      value: {
        modules: { Classic: { source() { return 'host'; } } },
        hostValue: 'kept',
      },
      enumerable: true,
    });
  )";
} // namespace

TEST(host_namespace_modules_join_the_namespace_the_host_already_installed) {
  Attached host(kHostNamespace);
  CHECK_EQ(host.eval("expo.modules.Joined.answer()"), "42");
  CHECK_EQ(host.eval("expo.modules.Classic.source()"), "host");
  CHECK_EQ(host.eval("expo.hostValue"), "kept");
}

TEST(host_namespace_a_module_the_host_has_wins_over_one_of_the_same_name) {
  Attached host(kHostNamespace);
  CHECK_EQ(host.eval("expo.modules.Classic.source()"), "host");
}

TEST(host_namespace_lists_the_hosts_modules_and_ours_once_each) {
  Attached host(kHostNamespace);
  CHECK_EQ(host.eval("Object.keys(expo.modules).sort()"), "Classic,Joined");
}

TEST(host_namespace_a_name_neither_side_has_is_undefined) {
  Attached host(kHostNamespace);
  CHECK_EQ(host.eval("typeof expo.modules.Missing"), "undefined");
}

TEST(host_namespace_without_modules_gets_ours) {
  Attached host("globalThis.expo = { hostValue: 'kept' };");
  CHECK_EQ(host.eval("expo.modules.Joined.answer()"), "42");
  CHECK_EQ(host.eval("expo.hostValue"), "kept");
  CHECK_EQ(host.eval("expo.modules.Classic.source()"), "cpp");
}

TEST(host_namespace_without_any_namespace_creates_it) {
  Attached host("");
  CHECK_EQ(host.eval("expo.modules.Joined.answer()"), "42");
}

TEST(host_namespace_the_global_can_be_named_otherwise) {
  auto runtime = facebook::hermes::makeHermesRuntime();
  auto context = cpp::Context::create(false);
  context->emplace<Joined>("Joined");
  auto attached = cpp::AttachedRuntime::attach(
    *runtime,
    context,
    std::make_shared<cpp::ManualScheduler>(),
    {.globalName = "custom"}
  );
  CHECK_EQ(
    runtime->evaluateJavaScript(std::make_shared<js::StringBuffer>("String(custom.modules.Joined.answer())"), "t.js")
           .toString(*runtime)
           .utf8(*runtime),
    "42"
  );
}

TEST(host_namespace_the_global_can_be_left_to_the_caller) {
  auto runtime = facebook::hermes::makeHermesRuntime();
  auto context = cpp::Context::create(false);
  context->emplace<Joined>("Joined");
  auto attached = cpp::AttachedRuntime::attach(
    *runtime,
    context,
    std::make_shared<cpp::ManualScheduler>(),
    {.installGlobal = false}
  );
  CHECK_EQ(
    runtime->evaluateJavaScript(std::make_shared<js::StringBuffer>("typeof globalThis.expo"), "t.js").toString(*runtime).utf8(*runtime),
    "undefined"
  );

  // The caller serves the modules itself, asking the attachment for them.
  const js::Value joined = attached->module("Joined");
  CHECK(joined.isObject());
  CHECK(attached->module("Missing").isUndefined());
  CHECK_EQ(attached->moduleNames().size(), size_t(1));
}

TEST(host_namespace_a_runtime_has_one_attachment) {
  auto runtime = facebook::hermes::makeHermesRuntime();
  auto context = cpp::Context::create(false);
  auto scheduler = std::make_shared<cpp::ManualScheduler>();
  auto first = cpp::AttachedRuntime::attach(*runtime, context, scheduler);
  CHECK_THROWS(cpp::AttachedRuntime::attach(*runtime, context, scheduler));
}

TEST(host_namespace_a_detached_modules_object_says_so) {
  auto runtime = facebook::hermes::makeHermesRuntime();
  auto context = cpp::Context::create(false);
  context->emplace<Joined>("Joined");
  auto attached = cpp::AttachedRuntime::attach(*runtime, context, std::make_shared<cpp::ManualScheduler>());
  runtime->evaluateJavaScript(std::make_shared<js::StringBuffer>("globalThis.modules = expo.modules;"), "t.js");

  attached.reset();
  try {
    runtime->evaluateJavaScript(std::make_shared<js::StringBuffer>("modules.Joined"), "t.js");
    CHECK(false);
  } catch (const js::JSError& error) {
    CHECK_CONTAINS(error.getMessage(), "detached");
  }
}

TEST(host_namespace_a_module_that_fails_to_define_reports_which) {
  class Broken : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("a", [] {});
      def.function("a", [] {});
    }
  };

  auto runtime = facebook::hermes::makeHermesRuntime();
  auto context = cpp::Context::create(true);
  // The first access instantiates the module and runs its definition.
  struct Registrar {
    Registrar() {
      cpp::detail::registerModuleFactory({
        .name = "BrokenStatic",
        .create = [] { return std::static_pointer_cast<cpp::Module>(std::make_shared<Broken>()); },
        .typeKey = cpp::detail::typeKey<Broken>(),
      });
    }
  } registrar;
  auto attached = cpp::AttachedRuntime::attach(*runtime, context, std::make_shared<cpp::ManualScheduler>());
  try {
    runtime->evaluateJavaScript(std::make_shared<js::StringBuffer>("expo.modules.BrokenStatic"), "t.js");
    CHECK(false);
  } catch (const js::JSError& error) {
    CHECK_CONTAINS(error.getMessage(), "Failed to materialize module 'BrokenStatic'");
    CHECK_CONTAINS(error.getMessage(), "already declared");
  }
}
