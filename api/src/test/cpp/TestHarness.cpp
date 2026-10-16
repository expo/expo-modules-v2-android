#include "TestHarness.h"

#include <chrono>
#include <cstdio>
#include <cstring>
#include <exception>

namespace test {
  std::vector<Case>& cases() {
    static std::vector<Case> list;
    return list;
  }

  void fail(const char* file, const int line, const std::string& message) {
    throw Failure{std::string(file) + ":" + std::to_string(line) + ": " + message};
  }
} // namespace test

int main(const int argc, char** argv) {
  // `expo-modules-cpp-tests <substring>` runs the tests whose name contains it.
  const char* filter = argc > 1 ? argv[1] : nullptr;

  int ran = 0;
  std::vector<std::string> failures;
  for (const test::Case& testCase: test::cases()) {
    if (filter != nullptr && std::strstr(testCase.name, filter) == nullptr) {
      continue;
    }
    ran++;

    const auto start = std::chrono::steady_clock::now();
    try {
      testCase.run();
      const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start);
      std::printf("[ ok ] %s (%lld ms)\n", testCase.name, static_cast<long long>(elapsed.count()));
    } catch (const test::Failure& failure) {
      std::printf("[FAIL] %s\n  %s\n", testCase.name, failure.message.c_str());
      failures.push_back(testCase.name);
    } catch (const std::exception& error) {
      std::printf("[FAIL] %s\n  unexpected exception: %s\n", testCase.name, error.what());
      failures.push_back(testCase.name);
    } catch (...) {
      std::printf("[FAIL] %s\n  unexpected exception of an unknown type\n", testCase.name);
      failures.push_back(testCase.name);
    }
    std::fflush(stdout);
  }

  std::printf("\n%d test(s) ran, %zu failed\n", ran, failures.size());
  return failures.empty() ? 0 : 1;
}
