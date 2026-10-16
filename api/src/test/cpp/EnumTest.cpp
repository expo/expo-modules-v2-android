#include "Fixture.h"

namespace {
  /** No value, so JavaScript sees each entry by its name. */
  enum class Size { Small, Large };
  EXPO_ENUM(Size, Small, Large)

  /** One string each, so JavaScript sees each entry by that value. */
  enum class Style { Light, Heavy };
  EXPO_ENUM(Style, (Light, "light"), (Heavy, "heavy"))

  /** One integer each, so JavaScript sees each entry as that number. */
  enum class Priority { Low = 0, Normal = 5, High = 10 };
  EXPO_ENUM(Priority, (Low, 0), (Normal, 5), (High, 10))

  struct Options {
    Style style = Style::Light;
    std::optional<Size> size;
  };
  EXPO_RECORD(Options, (style, optional), size)

  struct Task {
    Priority priority = Priority::Normal;
    std::optional<Priority> fallback;
  };
  EXPO_RECORD(Task, (priority, optional), (fallback, optional))

  const char* nameOf(const Style style) { return style == Style::Light ? "LIGHT" : "HEAVY"; }

  const char* nameOf(const Priority priority) {
    switch (priority) {
      case Priority::Low: return "LOW";
      case Priority::Normal: return "NORMAL";
      case Priority::High: return "HIGH";
    }
    return "?";
  }

  class Enums : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("echoSize", [](Size size) { return size; });
      def.function("nameOf", [](Style style) { return std::string(nameOf(style)); });
      def.function("styleNamed", [](std::string name) { return name == "LIGHT" ? Style::Light : Style::Heavy; });
      def.function("maybe", [](std::optional<Style> style) { return style.has_value() ? std::string(nameOf(*style)) : std::string("none"); });
      def.function("describe", [](Options options) {
        return std::string(options.style == Style::Light ? "light" : "heavy") + "/" + (options.size ? (*options.size == Size::Small ? "Small" : "Large") : "none");
      });
      def.function("options", [](Style style) { return Options{style, Size::Large}; });
      def.function("reversed", [](std::vector<Style> styles) {
        std::reverse(styles.begin(), styles.end());
        return styles;
      });
      def.function("later", [](Style style) -> cpp::Task<Style> { co_return style; });
      def.function("nameOfPriority", [](Priority priority) { return std::string(nameOf(priority)); });
      def.function("priorityNamed", [](std::string name) { return name == "LOW" ? Priority::Low : name == "NORMAL" ? Priority::Normal : Priority::High; });
      def.function("maybePriority", [](std::optional<Priority> priority) { return priority.has_value() ? std::string(nameOf(*priority)) : std::string("none"); });
      def.function("nextPriority", [](std::optional<Priority> priority) -> std::optional<Priority> {
        if (!priority) return std::nullopt;
        if (*priority == Priority::Low) return Priority::Normal;
        if (*priority == Priority::Normal) return Priority::High;
        return std::nullopt;
      });
      def.function("describeTask", [](Task task) {
        return std::to_string(static_cast<int>(task.priority)) + "/" + (task.fallback ? std::to_string(static_cast<int>(*task.fallback)) : "null");
      });
      def.function("task", [](Priority priority) { return Task{priority, Priority::Low}; });
      def.function("reversedPriorities", [](std::vector<Priority> priorities) {
        std::reverse(priorities.begin(), priorities.end());
        return priorities;
      });
      def.function("laterPriority", [](Priority priority) -> cpp::Task<Priority> { co_return priority; });
    }
  };

  struct Env : JsEnv {
    Env() { context().emplace<Enums>("Enums"); }

    std::string settled(const std::string& call) {
      exec("globalThis.out = null; " + call + ".then((v) => { globalThis.out = typeof v + ':' + v; });");
      pumpUntil("globalThis.out !== null");
      return eval("globalThis.out");
    }
  };
} // namespace

TEST(enum_an_enum_listed_by_name_crosses_as_its_name) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Enums.echoSize('Small')"), "Small");
  CHECK_EQ(env.eval("expo.modules.Enums.echoSize('Large')"), "Large");
}

TEST(enum_an_enum_with_string_values_crosses_as_that_value) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Enums.nameOf('heavy')"), "HEAVY");
  CHECK_EQ(env.eval("expo.modules.Enums.styleNamed('LIGHT')"), "light");
  CHECK_EQ(env.eval("expo.modules.Enums.styleNamed('HEAVY')"), "heavy");
}

TEST(enum_an_optional_enum_takes_null_and_can_be_left_out) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Enums.maybe(null)"), "none");
  CHECK_EQ(env.eval("expo.modules.Enums.maybe()"), "none");
  CHECK_EQ(env.eval("expo.modules.Enums.maybe('heavy')"), "HEAVY");
}

TEST(enum_a_record_field_can_be_an_enum_with_a_default) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Enums.describe({})"), "light/none");
  CHECK_EQ(env.eval("expo.modules.Enums.describe({ style: 'heavy', size: 'Large' })"), "heavy/Large");
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Enums.options('heavy'))"), R"({"style":"heavy","size":"Large"})");
}

TEST(enum_a_list_of_enums_crosses_element_by_element) {
  Env env;
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Enums.reversed(['light', 'heavy']))"), R"(["heavy","light"])");
}

TEST(enum_a_coroutine_takes_and_returns_an_enum) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Enums.later('heavy')"), "string:heavy");
}

TEST(enum_an_unknown_value_throws_an_error_that_lists_the_accepted_ones) {
  Env env;
  const std::string message = env.errorOf("expo.modules.Enums.nameOf('medium')");
  CHECK_CONTAINS(message, "'medium'");
  CHECK_CONTAINS(message, "is not a valid Style");
  CHECK_CONTAINS(message, "'light', 'heavy'");
}

TEST(enum_a_value_of_the_wrong_kind_is_a_type_error) {
  Env env;
  CHECK_CONTAINS(env.errorOf("expo.modules.Enums.nameOf(1)"), "expected a string, got a number");
  CHECK_CONTAINS(env.errorOf("expo.modules.Enums.nameOfPriority('HIGH')"), "expected a number, got a string");
}

TEST(enum_an_enum_with_integer_values_crosses_as_that_number) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Enums.nameOfPriority(10)"), "HIGH");
  CHECK_EQ(env.eval("expo.modules.Enums.priorityNamed('NORMAL')"), "5");
  CHECK_EQ(env.eval("typeof expo.modules.Enums.priorityNamed('LOW')"), "number");
}

TEST(enum_an_optional_integer_enum_takes_and_returns_null) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Enums.maybePriority(null)"), "none");
  CHECK_EQ(env.eval("expo.modules.Enums.maybePriority(0)"), "LOW");
  CHECK_EQ(env.eval("expo.modules.Enums.nextPriority(5)"), "10");
  CHECK_EQ(env.eval("expo.modules.Enums.nextPriority(10)"), "null");
}

TEST(enum_a_record_field_can_be_an_integer_enum_with_a_default) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Enums.describeTask({})"), "5/null");
  CHECK_EQ(env.eval("expo.modules.Enums.describeTask({ priority: 10, fallback: 0 })"), "10/0");
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Enums.task(10))"), R"({"priority":10,"fallback":0})");
}

TEST(enum_a_list_of_integer_enums_crosses_element_by_element) {
  Env env;
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Enums.reversedPriorities([5, 0, 10]))"), "[10,0,5]");
}

TEST(enum_a_coroutine_takes_and_returns_an_integer_enum) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Enums.laterPriority(5)"), "number:5");
}

TEST(enum_an_unknown_number_throws_an_error_that_lists_the_accepted_ones) {
  Env env;
  const std::string message = env.errorOf("expo.modules.Enums.nameOfPriority(7)");
  CHECK_CONTAINS(message, "'7'");
  CHECK_CONTAINS(message, "is not a valid Priority");
  CHECK_CONTAINS(message, "0, 5, 10");
}

TEST(enum_a_number_that_is_not_an_entry_is_named_in_the_error_whatever_it_is) {
  Env env;
  CHECK_CONTAINS(env.errorOf("expo.modules.Enums.nameOfPriority(3)"), "'3' is not a valid Priority");
  CHECK_CONTAINS(env.errorOf("expo.modules.Enums.nameOfPriority(3.5)"), "'3.5' is not a valid Priority");
  // Not numbers a `long long` can hold, so printing one as an integer would be undefined.
  CHECK_CONTAINS(env.errorOf("expo.modules.Enums.nameOfPriority(NaN)"), "'NaN' is not a valid Priority");
  CHECK_CONTAINS(env.errorOf("expo.modules.Enums.nameOfPriority(Infinity)"), "'Infinity' is not a valid Priority");
  CHECK_CONTAINS(env.errorOf("expo.modules.Enums.nameOfPriority(1e300)"), "'1e+300' is not a valid Priority");
}
