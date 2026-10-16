#include <expo-modules-cpp/Module.h>

#include <expo-modules-cpp/Definition.h>

namespace expo::modules::v2::cpp {
  Module::~Module() = default;

  void Module::define(ModuleDefinition&) {
  }

  void Module::prepare(const std::string& name) {
    spec_ = detail::ModuleSpec{};
    ModuleDefinition definition(spec_, name);
    define(definition);
  }
} // namespace expo::modules::v2::cpp
