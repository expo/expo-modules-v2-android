#include "Fixture.h"

namespace {
  struct Point {
    double x = 0;
    double y = 0;
  };
  EXPO_RECORD(Point, x, y)

  struct Person {
    std::string name;
    int age = 0;
    std::optional<std::string> nickname;
    std::vector<std::string> tags;
    Point home;
    std::vector<Point> visited;
  };
  EXPO_RECORD(Person, name, (age, optional), nickname, (tags, optional), (home, optional), (visited, optional))

  struct Strict {
    int required = 0;
    std::string text;
  };
  EXPO_RECORD(Strict, required, text)

  struct Defaults {
    int retries = 3;
    std::string mode = "fast";
    bool verbose = false;
  };
  EXPO_RECORD(Defaults, (retries, optional), (mode, optional), (verbose, optional))

  class Records : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("swap", [](Point point) { return Point{point.y, point.x}; });
      def.function("describe", [](const Person& person) {
        return person.name + "/" + std::to_string(person.age) + "/" + person.nickname.value_or("none") + "/" +
               std::to_string(person.tags.size()) + "/" + std::to_string(person.home.x) + "/" + std::to_string(person.visited.size());
      });
      def.function("person", [](std::string name) {
        Person person;
        person.name = std::move(name);
        person.age = 30;
        person.tags = {"a", "b"};
        person.home = Point{1, 2};
        person.visited = {Point{3, 4}, Point{5, 6}};
        return person;
      });
      def.function("strict", [](Strict value) { return value.text + std::to_string(value.required); });
      def.function("defaults", [](Defaults value) {
        return std::to_string(value.retries) + "/" + value.mode + "/" + (value.verbose ? "verbose" : "quiet");
      });
      def.function("points", [](std::vector<Point> points) {
        double total = 0;
        for (const Point& point: points) {
          total += point.x + point.y;
        }
        return total;
      });
      def.function("maybePoint", [](std::optional<Point> point) { return point.has_value() ? point->x : -1; });
    }
  };

  struct Env : JsEnv {
    Env() { context().emplace<Records>("Records"); }
  };
} // namespace

TEST(record_a_struct_is_a_javascript_object) {
  Env env;
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Records.swap({ x: 1, y: 2 }))"), R"({"x":2,"y":1})");
}

TEST(record_extra_properties_are_ignored) {
  Env env;
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Records.swap({ x: 1, y: 2, z: 3 }))"), R"({"x":2,"y":1})");
}

TEST(record_nested_records_and_lists_of_records_cross) {
  Env env;
  CHECK_EQ(
    env.eval(
      "expo.modules.Records.describe({ name: 'Ada', age: 36, nickname: 'A', tags: ['x'], home: { x: 7, y: 8 }, visited: [{ x: 1, y: 1 }, { x: 2, y: 2 }] })"
    ),
    "Ada/36/A/1/7.000000/2"
  );
  CHECK_EQ(
    env.eval("JSON.stringify(expo.modules.Records.person('Bo'))"),
    R"({"name":"Bo","age":30,"nickname":null,"tags":["a","b"],"home":{"x":1,"y":2},"visited":[{"x":3,"y":4},{"x":5,"y":6}]})"
  );
}

TEST(record_an_optional_field_may_be_left_out_and_keeps_its_default) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Records.defaults({})"), "3/fast/quiet");
  CHECK_EQ(env.eval("expo.modules.Records.defaults({ retries: 5 })"), "5/fast/quiet");
  CHECK_EQ(env.eval("expo.modules.Records.defaults({ mode: 'slow', verbose: true })"), "3/slow/verbose");
  CHECK_EQ(env.eval("expo.modules.Records.describe({ name: 'Ada' })"), "Ada/0/none/0/0.000000/0");
}

TEST(record_a_std_optional_field_may_be_left_out_or_null) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Records.describe({ name: 'Ada', nickname: null })"), "Ada/0/none/0/0.000000/0");
  CHECK_EQ(env.eval("expo.modules.Records.describe({ name: 'Ada', nickname: undefined })"), "Ada/0/none/0/0.000000/0");
}

TEST(record_a_missing_field_is_an_error_that_names_the_record_and_the_field) {
  Env env;
  const std::string message = env.errorOf("expo.modules.Records.strict({ text: 'x' })");
  CHECK_CONTAINS(message, "Invalid argument 1 of 'strict'");
  CHECK_CONTAINS(message, "Record 'Strict': field 'required' is null or missing but declared non-nullable");
  CHECK_CONTAINS(env.errorOf("expo.modules.Records.strict({ required: null, text: 'x' })"), "field 'required' is null or missing");
}

TEST(record_a_field_of_the_wrong_type_is_an_error_that_names_the_field) {
  Env env;
  CHECK_CONTAINS(
    env.errorOf("expo.modules.Records.strict({ required: 'one', text: 'x' })"),
    "Record 'Strict': field 'required': expected a number, got a string"
  );
}

TEST(record_something_that_is_not_an_object_is_an_error) {
  Env env;
  CHECK_CONTAINS(env.errorOf("expo.modules.Records.swap(5)"), "expected an object, got a number");
  CHECK_CONTAINS(env.errorOf("expo.modules.Records.swap(null)"), "expected an object, got null");
}

TEST(record_a_list_of_records_reports_the_bad_element) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Records.points([{ x: 1, y: 2 }, { x: 3, y: 4 }])"), "10");
  CHECK_CONTAINS(env.errorOf("expo.modules.Records.points([{ x: 1, y: 2 }, { x: 'a', y: 4 }])"), "field 'x'");
}

TEST(record_an_optional_record_takes_null) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Records.maybePoint(null)"), "-1");
  CHECK_EQ(env.eval("expo.modules.Records.maybePoint({ x: 4, y: 0 })"), "4");
  CHECK_EQ(env.eval("expo.modules.Records.maybePoint()"), "-1");
}

TEST(record_the_property_names_are_cached_per_runtime) {
  // Two runtimes convert the same record; each interns its own names, and neither sees the other's.
  auto context = cpp::Context::create(false);
  context->emplace<Records>("Records");
  JsEnv first(context);
  JsEnv second(context);
  CHECK_EQ(first.eval("JSON.stringify(expo.modules.Records.swap({ x: 1, y: 2 }))"), R"({"x":2,"y":1})");
  CHECK_EQ(second.eval("JSON.stringify(expo.modules.Records.swap({ x: 5, y: 6 }))"), R"({"x":6,"y":5})");
  CHECK_EQ(first.eval("JSON.stringify(expo.modules.Records.swap({ x: 3, y: 4 }))"), R"({"x":4,"y":3})");
}

namespace {
  struct Wrapper {
    Point inner;
    double after = 0;
    Person person;
  };
  EXPO_RECORD(Wrapper, inner, after, person)
} // namespace

TEST(record_a_nested_record_converts_outside_an_attached_runtime) {
  // Without an AttachedRuntime nothing caches the property names; each field's name must still be
  // its own, however deep the record is nested.
  auto runtime = facebook::hermes::makeHermesRuntime();
  js::Runtime& rt = *runtime;

  Wrapper wrapper;
  wrapper.inner = {1, 2};
  wrapper.after = 3;
  wrapper.person.name = "ann";
  wrapper.person.home = {4, 5};
  wrapper.person.visited = {{6, 7}};

  rt.global().setProperty(rt, "wrapper", cpp::Converter<Wrapper>::toJS(rt, wrapper));
  const auto evaluate = [&](const char* source) {
    return rt.evaluateJavaScript(std::make_shared<js::StringBuffer>(source), "test.js").toString(rt).utf8(rt);
  };
  CHECK_EQ(evaluate("Object.keys(wrapper).join()"), "inner,after,person");
  CHECK_EQ(evaluate("JSON.stringify(wrapper.inner) + JSON.stringify(wrapper.after)"), "{\"x\":1,\"y\":2}3");
  CHECK_EQ(evaluate("Object.keys(wrapper.person).join()"), "name,age,nickname,tags,home,visited");
  CHECK_EQ(evaluate("JSON.stringify(wrapper.person.visited)"), "[{\"x\":6,\"y\":7}]");

  const Wrapper back = cpp::Converter<Wrapper>::fromJS(rt, rt.global().getProperty(rt, "wrapper"));
  CHECK_EQ(back.inner.y, 2.0);
  CHECK_EQ(back.after, 3.0);
  CHECK_EQ(back.person.home.x, 4.0);
  CHECK_EQ(back.person.visited.size(), size_t{1});
}
