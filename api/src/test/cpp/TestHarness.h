#pragma once

#include <cmath>
#include <sstream>
#include <string>
#include <vector>

// A test runner small enough to read in a minute, so the suite depends on nothing but Hermes.
//
//   TEST(adds_numbers) {
//     CHECK_EQ(1 + 1, 2);
//   }

namespace test {
  struct Case {
    const char* name;
    void (*run)();
  };

  std::vector<Case>& cases();

  struct Registrar {
    Registrar(const char* name, void (*run)()) { cases().push_back({name, run}); }
  };

  /** Thrown by a failed check to leave the test that failed. */
  struct Failure {
    std::string message;
  };

  [[noreturn]] void fail(const char* file, int line, const std::string& message);

  template<typename T>
  std::string show(const T& value) {
    std::ostringstream out;
    out << value;
    return out.str();
  }

  inline std::string show(const bool value) { return value ? "true" : "false"; }

  inline std::string show(const std::string& value) { return "\"" + value + "\""; }

  inline std::string show(const char* value) { return std::string("\"") + value + "\""; }
} // namespace test

#define TEST(name) \
  static void test_##name(); \
  static ::test::Registrar registrar_##name(#name, &test_##name); \
  static void test_##name()

#define CHECK(condition) \
  do { \
    if (!(condition)) { \
      ::test::fail(__FILE__, __LINE__, "CHECK(" #condition ") failed"); \
    } \
  } while (false)

#define CHECK_EQ(actual, expected) \
  do { \
    const auto& actualValue = (actual); \
    const auto& expectedValue = (expected); \
    if (!(actualValue == expectedValue)) { \
      ::test::fail( \
        __FILE__, \
        __LINE__, \
        std::string("CHECK_EQ(" #actual ", " #expected ")\n    actual:   ") + ::test::show(actualValue) + \
          "\n    expected: " + ::test::show(expectedValue)); \
    } \
  } while (false)

/** Fails unless [haystack] contains [needle]. */
#define CHECK_CONTAINS(haystack, needle) \
  do { \
    const std::string haystackValue = (haystack); \
    if (haystackValue.find(needle) == std::string::npos) { \
      ::test::fail( \
        __FILE__, \
        __LINE__, \
        std::string("CHECK_CONTAINS(" #haystack ", " #needle ")\n    actual:   ") + ::test::show(haystackValue) + \
          "\n    missing:  " + ::test::show(std::string(needle))); \
    } \
  } while (false)

#define CHECK_THROWS(expression) \
  do { \
    bool threw = false; \
    try { \
      (void) (expression); \
    } catch (...) { \
      threw = true; \
    } \
    if (!threw) { \
      ::test::fail(__FILE__, __LINE__, "CHECK_THROWS(" #expression ") did not throw"); \
    } \
  } while (false)
