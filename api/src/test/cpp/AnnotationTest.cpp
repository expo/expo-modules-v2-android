#include "Fixture.h"

#include <atomic>

// The exports of every class here are annotations; nothing calls `def.function`.

namespace {
  enum class Mode { Fast, Slow };
  EXPO_ENUM(Mode, Fast, Slow)

  struct Point {
    double x = 0;
    double y = 0;
  };
  EXPO_RECORD(Point, x, y)

  std::atomic<int> gTicketsDestroyed{0};

  class Ticket : public cpp::ExpoSharedObject<Ticket> {
  public:
    EXPO_JS_CONSTRUCTOR(std::string, int)
    Ticket(std::string text, const int copies) : text_(std::move(text)), copies_(copies) {
    }

    ~Ticket() override { gTicketsDestroyed++; }

    EXPO_JS_CONSTANT(label)
    std::string label() {
      labelReads++;
      return "#" + text_;
    }

    EXPO_JS(labelReads)
    int labelReads = 0;

    EXPO_JS(copies)
    int copies() const { return copies_; }

    EXPO_JS(notes)
    std::vector<std::string> notes;

    EXPO_JS(print)
    void print() { onPrinted.emit(text_); }

    EXPO_EVENT(onPrinted, std::string);

  private:
    std::string text_;
    int copies_;
  };

  class Animal : public cpp::ExpoSharedObject<Animal> {
  public:
    EXPO_JS_CONSTRUCTOR(std::string)
    explicit Animal(std::string name) : name_(std::move(name)) {
    }

    EXPO_JS_GETTER(getName)
    std::string getName() const { return name_; }

    EXPO_JS(speak)
    std::string speak() { return name_ + " makes a sound"; }

  protected:
    std::string name_;
  };

  class Dog : public cpp::ExpoSharedObject<Dog, Animal> {
  public:
    EXPO_JS_CONSTRUCTOR(std::string)
    explicit Dog(std::string name) : cpp::ExpoSharedObject<Dog, Animal>(std::move(name)) {
    }

    EXPO_JS(fetch)
    std::string fetch() { return name_ + " fetches"; }
  };

  class Named : public cpp::ExpoSharedObject<Named> {
  public:
    EXPO_JS_NAME("Revealed")
    EXPO_JS_CONSTRUCTOR()
    Named() = default;

    EXPO_JS(secret)
    int secret() { return 7; }
  };

  /** Has no constructor, so JavaScript only ever receives one. */
  class Handle : public cpp::ExpoSharedObject<Handle> {
  public:
    EXPO_JS(open)
    bool open() { return true; }
  };

  class Notes : public cpp::ExpoModule<Notes> {
  public:
    EXPO_JS(add)
    int add(const int a, const int b) { return a + b; }

    EXPO_JS(multiply, "times")
    int multiply(const int a, const int b) { return a * b; }

    EXPO_JS(ping)
    static std::string ping() { return "pong"; }

    EXPO_JS(volume)
    double volume = 1;

    EXPO_JS(limit)
    const int limit = 10;

    EXPO_JS_GETTER(getLive)
    int getLive() { return ++liveReads; }

    EXPO_JS_PROPERTY(getLevel, setLevel)
    int getLevel() const { return level_; }
    void setLevel(const int value) { level_ = value; }

    EXPO_JS_PROPERTY(getTone, setTone, "pitch")
    std::string getTone() const { return tone_; }
    void setTone(std::string value) { tone_ = std::move(value); }

    EXPO_JS_CONSTANT(version)
    static constexpr int version = 3;

    EXPO_JS_CONSTANT(answer)
    int answer() {
      answerReads++;
      return 42;
    }

    EXPO_JS_CONSTANT(startedAt, "started")
    int startedAt = 5;

    EXPO_JS(later)
    cpp::Task<int> later(const int value) { co_return value * 2; }

    EXPO_JS(settle)
    void settle(cpp::Promise<int> promise, const int value) { promise.resolve(value + 1); }

    EXPO_JS(onThisThread)
    bool onThisThread(js::Runtime& rt) { return cpp::isOnJSThread(rt); }

    EXPO_JS(describeMode)
    std::string describeMode(const Mode mode) { return mode == Mode::Fast ? "fast" : "slow"; }

    EXPO_JS(flip)
    Point flip(const Point point) { return {point.y, point.x}; }

    EXPO_JS(fire)
    void fire(const int value) {
      onChanged.emit(value);
      online.emit(value);
      onPing.emit();
    }

    EXPO_JS(makeTicket)
    std::shared_ptr<Ticket> makeTicket() { return std::make_shared<Ticket>("made", 1); }

    EXPO_JS(makeHandle)
    std::shared_ptr<Handle> makeHandle() { return std::make_shared<Handle>(); }

    EXPO_JS_CLASS(Ticket)
    EXPO_JS_CLASS(Animal)
    EXPO_JS_CLASS(Dog, "Doggo")
    EXPO_JS_CLASS(Named)
    EXPO_JS_CLASS(Handle)

    EXPO_EVENT(onChanged, int);
    EXPO_EVENT(online, int);
    EXPO_EVENT(onPing);

    int liveReads = 0;
    int answerReads = 0;

  private:
    // An annotation works in any section: it is a member, so it sees the private ones too.
    EXPO_JS(inside)
    int inside() const { return level_ + 100; }

    int level_ = 1;
    std::string tone_ = "low";
  };

  struct Env : JsEnv {
    std::shared_ptr<Notes> notes;

    Env() {
      notes = context().emplace<Notes>("Notes");
      exec("globalThis.seen = [];");
    }
  };
} // namespace

TEST(annotation_a_member_function_is_a_function_under_its_own_name_or_the_one_given) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Notes.add(2, 3)"), "5");
  CHECK_EQ(env.eval("expo.modules.Notes.multiply"), "undefined");
  CHECK_EQ(env.eval("expo.modules.Notes.times(4, 5)"), "20");
  CHECK_EQ(env.eval("expo.modules.Notes.ping()"), "pong");
  CHECK_CONTAINS(env.errorOf("expo.modules.Notes.add(1)"), "add");
  CHECK_CONTAINS(env.errorOf("expo.modules.Notes.add('a', 1)"), "expected a number");
}

TEST(annotation_a_private_member_is_exported_too) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Notes.inside()"), "101");
}

TEST(annotation_a_data_member_is_a_property_and_a_const_one_is_read_only) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Notes.volume"), "1");
  env.exec("expo.modules.Notes.volume = 0.5;");
  CHECK_EQ(env.notes->volume, 0.5);
  CHECK_EQ(env.eval("expo.modules.Notes.volume"), "0.5");

  CHECK_EQ(env.eval("expo.modules.Notes.limit"), "10");
  CHECK_EQ(env.eval("typeof Object.getOwnPropertyDescriptor(expo.modules.Notes, 'limit').set"), "undefined");
}

TEST(annotation_a_getter_drops_its_get_prefix_and_a_pair_is_one_property) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Notes.live + ',' + expo.modules.Notes.live"), "1,2");
  CHECK_EQ(env.eval("expo.modules.Notes.getLive"), "undefined");
  CHECK_EQ(env.eval("typeof Object.getOwnPropertyDescriptor(expo.modules.Notes, 'live').set"), "undefined");

  CHECK_EQ(env.eval("expo.modules.Notes.level"), "1");
  env.exec("expo.modules.Notes.level = 8;");
  CHECK_EQ(env.eval("expo.modules.Notes.level"), "8");

  // A third argument names the pair.
  CHECK_EQ(env.eval("expo.modules.Notes.pitch"), "low");
  env.exec("expo.modules.Notes.pitch = 'high';");
  CHECK_EQ(env.eval("expo.modules.Notes.pitch"), "high");
  CHECK_EQ(env.eval("expo.modules.Notes.tone"), "undefined");
}

TEST(annotation_a_constant_is_read_once_and_a_static_one_is_a_value) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Notes.version"), "3");
  CHECK_EQ(env.eval("expo.modules.Notes.answer + expo.modules.Notes.answer"), "84");
  CHECK_EQ(env.notes->answerReads, 1);

  CHECK_EQ(env.eval("expo.modules.Notes.started"), "5");
  CHECK_EQ(env.eval("expo.modules.Notes.startedAt"), "undefined");
  CHECK_EQ(env.eval("typeof Object.getOwnPropertyDescriptor(expo.modules.Notes, 'version').set"), "undefined");
}

TEST(annotation_coroutines_promises_the_runtime_and_conversions_work_as_they_do_in_define) {
  Env env;
  env.exec("globalThis.out = []; expo.modules.Notes.later(4).then((v) => out.push('later ' + v));");
  env.exec("expo.modules.Notes.settle(5).then((v) => out.push('settle ' + v));");
  env.pumpUntil("out.length === 2");
  CHECK_EQ(env.eval("out.slice().sort().join()"), "later 8,settle 6");

  CHECK_EQ(env.eval("expo.modules.Notes.onThisThread()"), "true");
  CHECK_EQ(env.eval("expo.modules.Notes.describeMode('Slow')"), "slow");
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Notes.flip({ x: 1, y: 2 }))"), "{\"x\":2,\"y\":1}");
}

TEST(annotation_an_event_is_named_the_way_kotlin_names_it) {
  Env env;
  env.exec(
    "expo.modules.Notes.addListener('changed', (n) => seen.push('changed ' + n));"
    "expo.modules.Notes.addListener('online', (n) => seen.push('online ' + n));"
    "expo.modules.Notes.addListener('ping', () => seen.push('ping'));"
    "expo.modules.Notes.fire(7);"
  );
  CHECK_EQ(env.eval("seen.join()"), "changed 7,online 7,ping");

  // `online` does not start with `on` and a capital, so it keeps its name; the others lose theirs.
  CHECK_CONTAINS(env.errorOf("expo.modules.Notes.addListener('onChanged', () => {})"), "'onChanged' is not an event");
  CHECK_CONTAINS(env.errorOf("expo.modules.Notes.addListener('x', () => {})"), "'changed', 'online', 'ping'");
}

TEST(annotation_the_names_follow_the_rules_for_events_and_getters) {
  CHECK_EQ(cpp::detail::eventNameOf("onChanged"), "changed");
  CHECK_EQ(cpp::detail::eventNameOf("onURLChange"), "uRLChange");
  CHECK_EQ(cpp::detail::eventNameOf("online"), "online");
  CHECK_EQ(cpp::detail::eventNameOf("on"), "on");
  CHECK_EQ(cpp::detail::eventNameOf("onX"), "x");
  CHECK_EQ(cpp::detail::eventNameOf("changed"), "changed");
  CHECK_EQ(cpp::detail::eventNameOf(""), "");

  CHECK_EQ(cpp::detail::propertyNameOf("getVolume"), "volume");
  CHECK_EQ(cpp::detail::propertyNameOf("volume"), "volume");
  CHECK_EQ(cpp::detail::propertyNameOf("getter"), "getter");
  CHECK_EQ(cpp::detail::propertyNameOf("get"), "get");
  CHECK_EQ(cpp::detail::propertyNameOf("isActive"), "isActive");
}

TEST(annotation_a_shared_object_class_is_exported_by_the_module_and_built_with_new) {
  Env env;
  CHECK_EQ(env.eval("globalThis.t = new expo.modules.Notes.Ticket('vip', 3); t.copies() + ',' + t.print.length"), "3,0");
  CHECK_EQ(env.eval("t instanceof expo.modules.Notes.Ticket"), "true");
  CHECK_CONTAINS(env.errorOf("new expo.modules.Notes.Ticket('vip')"), "Ticket");
  CHECK_CONTAINS(env.errorOf("new expo.modules.Notes.Ticket(1, 2)"), "expected a string");
}

TEST(annotation_a_shared_objects_properties_constants_and_events_work) {
  Env env;
  env.exec("globalThis.t = new expo.modules.Notes.Ticket('vip', 3);");
  CHECK_EQ(env.eval("t.label + t.label + ',' + t.labelReads"), "#vip#vip,1");

  env.exec("t.notes = ['a', 'b'];");
  CHECK_EQ(env.eval("t.notes.join()"), "a,b");

  env.exec("t.addListener('printed', (text) => seen.push(text)); t.print();");
  CHECK_EQ(env.eval("seen.join()"), "vip");
}

TEST(annotation_a_constant_of_a_shared_object_is_read_once_per_instance) {
  Env env;
  env.exec("globalThis.a = new expo.modules.Notes.Ticket('a', 1); globalThis.b = new expo.modules.Notes.Ticket('b', 1);");
  CHECK_EQ(env.eval("a.label + a.label + b.label + ',' + a.labelReads + b.labelReads"), "#a#a#b,11");
}

TEST(annotation_a_class_that_extends_another_inherits_its_members) {
  Env env;
  env.exec("globalThis.d = new expo.modules.Notes.Doggo('rex');");
  CHECK_EQ(env.eval("d.fetch()"), "rex fetches");
  CHECK_EQ(env.eval("d.speak()"), "rex makes a sound");
  CHECK_EQ(env.eval("d.name"), "rex");
  CHECK_EQ(env.eval("d instanceof expo.modules.Notes.Animal"), "true");
  CHECK_EQ(env.eval("d instanceof expo.modules.Notes.Doggo"), "true");
  CHECK_EQ(env.eval("new expo.modules.Notes.Animal('cat') instanceof expo.modules.Notes.Doggo"), "false");
}

TEST(annotation_a_shared_object_class_can_be_renamed_and_one_without_a_constructor_is_only_received) {
  Env env;
  CHECK_EQ(env.eval("new expo.modules.Notes.Revealed().secret()"), "7");
  CHECK_EQ(env.eval("expo.modules.Notes.Named"), "undefined");
  // The name is applied before the constructor is described, wherever the two are written.
  CHECK_CONTAINS(env.errorOf("new expo.modules.Notes.Revealed(1)"), "Revealed");

  CHECK_EQ(env.eval("expo.modules.Notes.Handle"), "undefined");
  CHECK_EQ(env.eval("expo.modules.Notes.makeHandle().open()"), "true");
}

TEST(annotation_a_shared_object_returned_by_a_member_is_the_class_it_is) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Notes.makeTicket() instanceof expo.modules.Notes.Ticket"), "true");
  CHECK_EQ(env.eval("expo.modules.Notes.makeTicket().label"), "#made");
}

TEST(annotation_shared_objects_are_collected_like_any_other) {
  gTicketsDestroyed = 0;
  Env env;
  env.exec("new expo.modules.Notes.Ticket('gone', 1); 0");
  for (int pass = 0; pass < 10 && gTicketsDestroyed == 0; pass++) {
    env.rt().instrumentation().collectGarbage("test");
  }
  CHECK_EQ(gTicketsDestroyed.load(), 1);
}

namespace {
  class Extended : public cpp::ExpoModule<Extended> {
  public:
    EXPO_JS(annotated)
    int annotated() { return 1; }

    void define(cpp::ModuleDefinition& def) override {
      cpp::ExpoModule<Extended>::define(def);
      def.function("written", [] { return 2; });
    }
  };

  class Clashing : public cpp::ExpoModule<Clashing> {
  public:
    EXPO_JS(first)
    int first() { return 1; }

    EXPO_JS(second, "first")
    int second() { return 2; }
  };

  class Empty : public cpp::ExpoModule<Empty> {};

  /** Renamed after its members clash, wherever the name is written. */
  class Clashed : public cpp::ExpoSharedObject<Clashed> {
  public:
    EXPO_JS(first)
    int first() { return 1; }

    EXPO_JS(second, "first")
    int second() { return 2; }

    EXPO_JS_NAME("Shown")
  };

  class Compact : public cpp::ExpoModule<Compact> {
  public:
    // Annotations of different members may share a line.
    EXPO_JS(one) int one() { return 1; } EXPO_JS(two) int two() { return 2; }
  };
} // namespace

TEST(annotation_a_module_can_add_exports_of_its_own_to_the_annotated_ones) {
  JsEnv env;
  env.context().emplace<Extended>("Extended");
  CHECK_EQ(env.eval("expo.modules.Extended.annotated() + expo.modules.Extended.written()"), "3");
}

TEST(annotation_two_exports_under_one_name_are_refused_as_they_are_in_define) {
  JsEnv env;
  CHECK_THROWS(env.context().emplace<Clashing>("Clashing"));
}

TEST(annotation_a_module_without_annotations_exports_nothing_but_the_emitter) {
  JsEnv env;
  env.context().emplace<Empty>("Empty");
  CHECK_EQ(env.eval("Object.keys(expo.modules.Empty).length"), "0");
  CHECK_EQ(env.eval("typeof expo.modules.Empty.addListener"), "function");
}

TEST(annotation_each_context_defines_the_module_from_the_same_annotations) {
  Env first;
  Env second;
  CHECK_EQ(first.eval("expo.modules.Notes.add(1, 1)"), "2");
  CHECK_EQ(second.eval("expo.modules.Notes.add(2, 2)"), "4");
  CHECK(first.notes != second.notes);
}

TEST(annotation_annotations_of_different_members_may_share_a_line) {
  JsEnv env;
  env.context().emplace<Compact>("Compact");
  CHECK_EQ(env.eval("expo.modules.Compact.one() + expo.modules.Compact.two()"), "3");
}

TEST(annotation_a_shared_object_class_is_named_before_its_members_are_checked) {
  try {
    (void) cpp::detail::classSpecOf<Clashed>();
    CHECK(false);
  } catch (const std::invalid_argument& error) {
    CHECK_CONTAINS(std::string(error.what()), "Shown");
  }
}
