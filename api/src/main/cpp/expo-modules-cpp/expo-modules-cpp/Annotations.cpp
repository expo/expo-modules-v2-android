#include <expo-modules-cpp/Annotations.h>

#include <cctype>

namespace expo::modules::v2::cpp::detail {
  namespace {
    bool isUpper(const char character) {
      return std::isupper(static_cast<unsigned char>(character)) != 0;
    }

    /** [name] without its first [prefix] characters, whose own first letter is lower-cased. */
    std::string withoutPrefix(const std::string_view name, const size_t prefix) {
      std::string result(name.substr(prefix));
      result[0] = static_cast<char>(std::tolower(static_cast<unsigned char>(result[0])));
      return result;
    }
  } // namespace

  // The same rule as the Kotlin compiler plugin's `eventJsName`: `on` followed by a capital letter.
  std::string eventNameOf(const std::string_view member) {
    if (member.size() > 2 && member.starts_with("on") && isUpper(member[2])) {
      return withoutPrefix(member, 2);
    }
    return std::string(member);
  }

  std::string propertyNameOf(const std::string_view getter) {
    if (getter.size() > 3 && getter.starts_with("get") && isUpper(getter[3])) {
      return withoutPrefix(getter, 3);
    }
    return std::string(getter);
  }
} // namespace expo::modules::v2::cpp::detail
