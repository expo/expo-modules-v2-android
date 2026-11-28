#include "ExpoViewRegistry.h"

#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

#include <kolibri/native_method.h>
#include <kolibri/string_utils.h>
#include <react/fabric/CoreComponentsRegistry.h>

#include "ExpoViewComponentDescriptor.h"

namespace rn = facebook::react;

namespace expo::modules::v2::react::views {
  namespace {
    /**
     * Every flavor ever registered, by component name. Never freed: Fabric uses the address of a
     * flavor's name as the component handle, and registries outlive a reload.
     */
    std::mutex flavorsMutex;
    std::unordered_map<std::string, std::shared_ptr<const ExpoViewFlavor>> flavors;

    void registerView(JNIEnv* env, jstring jname, jobject decoder) {
      std::string name = kolibri::toStdString(env, jname);

      std::lock_guard lock(flavorsMutex);
      if (flavors.contains(name)) {
        return;
      }
      auto flavor = std::make_shared<const ExpoViewFlavor>(ExpoViewFlavor{
        .name = name,
        .codec = std::make_shared<const ViewPropsCodec>(env, decoder),
      });
      flavors.emplace(std::move(name), flavor);

      // The registry every `DefaultComponentsRegistry` builds from. Adding to it also adds the
      // descriptor to the registries already built from it, so this works before and after the
      // first surface starts.
      rn::CoreComponentsRegistry::sharedProviderRegistry()->add(ExpoViewComponentDescriptor::provider(flavor));
    }
  } // namespace

  void ExpoViewRegistry::registerNatives(JNIEnv* env) {
    JavaClass::registerNatives(env)
      .method("nativeRegister", [](JNIEnv* env, jstring name, jobject decoder) { registerView(env, name, decoder); })
      .commit();
  }
} // namespace expo::modules::v2::react::views
