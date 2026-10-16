#include "Fixture.h"

namespace {
  class Ticket : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Ticket>& cls) {
      cls.constructor<std::string>();
      // Computed at the first read of each instance, and then kept.
      cls.constant("label", [](Ticket& self) {
        self.reads_++;
        std::string upper = self.text_;
        for (char& c: upper) {
          c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
        }
        return upper;
      });
      cls.property("labelReads", &Ticket::reads_);
      cls.constant("shout", &Ticket::shout);
      cls.constant("size", 2);
      cls.constant("kind", "ticket");
      cls.property("notes", &Ticket::notes_);
      cls.function("addNote", [](Ticket& self, std::string note) { self.notes_.push_back(std::move(note)); });
    }

    explicit Ticket(std::string text) : text_(std::move(text)) {
    }

    std::string shout() { return text_ + "!"; }

    std::string text_;
    int reads_ = 0;
    std::vector<std::string> notes_{"x"};
  };

  class Settings : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.constant("answer", [this] {
        answerReads++;
        return 42;
      });
      def.constant("name", "expo");
      def.constant("version", 3);
      def.constant("pi", 3.5);
      def.constant("flag", true);
      def.property("live", [this] { return ++liveReads; });
      def.property("tags", [this] { return tags; });
      def.constant("flaky", [this] {
        if (flakyReads++ == 0) {
          throw std::runtime_error("not ready");
        }
        return 7;
      });
      def.property("mutableValue", this, &Settings::value);
      def.property("readOnlyValue", this, &Settings::fixed);
      def.property("viaMethods", this, &Settings::getViaMethods, &Settings::setViaMethods);
      def.property("viaLambdas", [this] { return lambdaValue; }, [this](std::string value) { lambdaValue = std::move(value); });
      def.sharedClass<Ticket>();
    }

    int answerReads = 0;
    int liveReads = 0;
    int flakyReads = 0;
    std::vector<std::string> tags{"a"};
    double value = 1;
    const int fixed = 9;
    std::string lambdaValue = "initial";

    int getViaMethods() const { return viaMethods_; }

    void setViaMethods(int value) { viaMethods_ = value * 2; }

  private:
    int viaMethods_ = 0;
  };

  struct Env : JsEnv {
    std::shared_ptr<Settings> settings;

    Env() { settings = context().emplace<Settings>("Settings"); }
  };
} // namespace

TEST(constant_a_constant_calls_its_getter_once_however_often_javascript_reads_it) {
  Env env;
  CHECK_EQ(env.eval("const s = expo.modules.Settings; [s.answer, s.answer, s.answer, s.name].join()"), "42,42,42,expo");
  CHECK_EQ(env.settings->answerReads, 1);
}

TEST(constant_after_its_first_read_a_constant_is_a_plain_read_only_value) {
  Env env;
  const std::string describe =
    "(() => { const d = Object.getOwnPropertyDescriptor(expo.modules.Settings, 'answer'); "
    "return [typeof d.get, 'value' in d, d.writable, d.enumerable].join(); })()";

  CHECK_EQ(env.eval(describe), "function,false,,true");
  env.exec("expo.modules.Settings.answer");
  CHECK_EQ(env.eval(describe), "undefined,true,false,true");
  CHECK_EQ(
    env.eval("(() => { 'use strict'; try { expo.modules.Settings.answer = 1; return false; } catch (e) { return e instanceof TypeError; } })()"),
    "true"
  );
}

TEST(constant_a_value_is_a_plain_read_only_property_from_the_start) {
  Env env;
  CHECK_EQ(
    env.eval(
      "(() => { const d = Object.getOwnPropertyDescriptor(expo.modules.Settings, 'version');"
      " return [typeof d.get, d.value, d.writable, d.enumerable, d.configurable].join(); })()"
    ),
    "undefined,3,false,true,false"
  );
  CHECK_EQ(env.eval("[expo.modules.Settings.pi, expo.modules.Settings.flag, expo.modules.Settings.name].join()"), "3.5,true,expo");
}

TEST(constant_a_property_that_is_not_a_constant_calls_its_getter_on_every_read) {
  Env env;
  CHECK_EQ(env.eval("const s = expo.modules.Settings; [s.live, s.live, s.live].join()"), "1,2,3");
  CHECK_EQ(env.settings->liveReads, 3);
}

TEST(constant_a_property_whose_contents_can_change_is_read_every_time) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Settings.tags.join()"), "a");
  env.settings->tags.push_back("b");
  CHECK_EQ(env.eval("expo.modules.Settings.tags.join()"), "a,b");
}

TEST(constant_a_constant_whose_getter_throws_is_read_again_next_time) {
  Env env;
  CHECK_EQ(
    env.eval(
      "(() => { const s = expo.modules.Settings; let first;"
      " try { first = s.flaky; } catch (e) { first = e.message; }"
      " return [first, s.flaky, s.flaky].join('|'); })()"
    ),
    "not ready|7|7"
  );
  CHECK_EQ(env.settings->flakyReads, 2);
}

TEST(constant_a_shared_objects_constant_calls_its_getter_once_per_instance) {
  Env env;
  CHECK_EQ(
    env.eval(
      "const B = expo.modules.Settings.Ticket; const a = new B('a'), b = new B('b');"
      "[a.label, a.label, b.label, a.labelReads, b.labelReads].join()"
    ),
    "A,A,B,1,1"
  );
}

TEST(constant_after_its_first_read_a_shared_objects_constant_is_the_instances_hidden_value) {
  Env env;
  CHECK_EQ(
    env.eval(
      "(() => { const B = expo.modules.Settings.Ticket; const a = new B('a'), b = new B('b');"
      " const before = Object.prototype.hasOwnProperty.call(a, 'label'); const value = a.label;"
      " const d = Object.getOwnPropertyDescriptor(a, 'label');"
      " return [before, value, [typeof d.get, 'value' in d, d.writable, d.enumerable].join(),"
      " Object.prototype.hasOwnProperty.call(b, 'label'), Object.keys(a).join()].join('|'); })()"
    ),
    "false|A|undefined,true,false,false|false|"
  );
}

TEST(constant_a_shared_object_value_constant_is_shared_by_every_instance_and_hidden) {
  Env env;
  CHECK_EQ(
    env.eval(
      "(() => { const a = new expo.modules.Settings.Ticket('a'); const b = new expo.modules.Settings.Ticket('b');"
      " return [a.size, b.size, a.kind, Object.keys(a).includes('size')].join(); })()"
    ),
    "2,2,ticket,false"
  );
}

TEST(constant_a_shared_object_property_whose_contents_can_change_is_read_every_time) {
  Env env;
  CHECK_EQ(
    env.eval(
      "(() => { const a = new expo.modules.Settings.Ticket('a'); const notes = a.notes.join();"
      " a.addNote('y'); return [notes, a.notes.join()].join('|'); })()"
    ),
    "x|x,y"
  );
}

TEST(property_a_data_member_is_read_and_written) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Settings.mutableValue"), "1");
  env.exec("expo.modules.Settings.mutableValue = 12.5");
  CHECK_EQ(env.settings->value, 12.5);
  CHECK_EQ(env.eval("expo.modules.Settings.mutableValue"), "12.5");
}

TEST(property_a_const_data_member_is_read_only) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Settings.readOnlyValue"), "9");
  CHECK_EQ(
    env.eval("(() => { 'use strict'; try { expo.modules.Settings.readOnlyValue = 1; return 'no error'; } catch (e) { return e instanceof TypeError; } })()"),
    "true"
  );
}

TEST(property_a_getter_and_a_setter_may_be_member_functions) {
  Env env;
  env.exec("expo.modules.Settings.viaMethods = 21");
  CHECK_EQ(env.eval("expo.modules.Settings.viaMethods"), "42");
}

TEST(property_a_getter_and_a_setter_may_be_lambdas) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Settings.viaLambdas"), "initial");
  env.exec("expo.modules.Settings.viaLambdas = 'changed'");
  CHECK_EQ(env.settings->lambdaValue, "changed");
}

TEST(property_a_setter_converts_its_value) {
  Env env;
  CHECK_CONTAINS(env.errorOf("expo.modules.Settings.mutableValue = 'text'"), "expected a number, got a string");
}

TEST(property_properties_are_enumerable_accessors_of_the_module_object) {
  Env env;
  CHECK_EQ(
    env.eval(
      "(() => { const d = Object.getOwnPropertyDescriptor(expo.modules.Settings, 'live');"
      " return [typeof d.get, typeof d.set, d.enumerable, d.configurable].join(); })()"
    ),
    "function,undefined,true,false"
  );
  CHECK_EQ(
    env.eval(
      "(() => { const d = Object.getOwnPropertyDescriptor(expo.modules.Settings, 'mutableValue');"
      " return [typeof d.get, typeof d.set].join(); })()"
    ),
    "function,function"
  );
}

TEST(constant_a_shared_object_constant_can_be_read_with_a_member_function_pointer) {
  JsEnv env;
  env.context().emplace<Settings>("Settings");
  CHECK_EQ(env.eval("new expo.modules.Settings.Ticket('hi').shout"), "hi!");
}
