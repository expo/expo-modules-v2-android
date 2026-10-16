#include <expo-modules-cpp/AttachedRuntime.h>

#include <optional>
#include <utility>

#include <expo-modules-cpp/internal/ModuleHost.h>
#include <expo-modules-cpp/internal/RuntimeData.h>

namespace expo::modules::v2::cpp {
  namespace {
    /**
     * The `modules` the host put under `expo`, if any. After an earlier attachment the object there
     * is that attachment's own host, which is detached by now: the modules it wrapped are the host's,
     * and the new host wraps those and not it.
     */
    std::optional<js::Object> hostModulesOf(js::Runtime& rt, const js::Object& expoNamespace) {
      const js::Value modules = expoNamespace.getProperty(rt, "modules");
      if (!modules.isObject()) {
        return std::nullopt;
      }

      js::Object object = modules.getObject(rt);
      if (object.isHostObject(rt)) {
        if (const std::shared_ptr<detail::ModuleHost> earlier = detail::ModuleHost::from(object.getHostObject(rt))) {
          const std::optional<js::Object>& wrapped = earlier->hostModules();
          return wrapped.has_value() ? std::optional(js::Value(rt, *wrapped).getObject(rt)) : std::nullopt;
        }
      }
      return object;
    }

    void installModulesObject(js::Runtime& rt, const std::string& globalName, std::shared_ptr<detail::RuntimeLink> link) {
      js::Value existing = rt.global().getProperty(rt, globalName.c_str());

      if (existing.isObject()) {
        // The host already owns the namespace (`expo-modules-core` installs `globalThis.expo` as a
        // non-writable global), so the modules join it instead of replacing it. The host's own
        // `modules` object stays reachable through the host object that takes its place.
        js::Object expoNamespace = existing.getObject(rt);
        auto host = std::make_shared<detail::ModuleHost>(std::move(link), hostModulesOf(rt, expoNamespace));
        expoNamespace.setProperty(rt, "modules", js::Object::createFromHostObject(rt, std::move(host)));
        return;
      }

      js::Object expoNamespace(rt);
      expoNamespace.setProperty(
        rt,
        "modules",
        js::Object::createFromHostObject(rt, std::make_shared<detail::ModuleHost>(std::move(link), std::nullopt))
      );
      rt.global().setProperty(rt, globalName.c_str(), std::move(expoNamespace));
    }
  } // namespace

  void runOnJSThread(js::Runtime& runtime, std::function<void(js::Runtime&)> job) {
    // The link, not the data: the data belongs to the JS thread, and this runs on any thread.
    const std::shared_ptr<detail::RuntimeLink> link = detail::RuntimeData::findLink(runtime);
    if (link == nullptr) {
      return;
    }
    link->runOnJS([job = std::move(job)](detail::RuntimeData& attached) { job(attached.runtime()); });
  }

  bool isOnJSThread(js::Runtime& runtime) {
    const std::shared_ptr<detail::RuntimeLink> link = detail::RuntimeData::findLink(runtime);
    return link != nullptr && link->isOnJSThread();
  }

  AttachedRuntime::AttachedRuntime(std::unique_ptr<detail::RuntimeData> data) : data_(std::move(data)) {
  }

  AttachedRuntime::~AttachedRuntime() = default;

  std::unique_ptr<AttachedRuntime> AttachedRuntime::attach(
    js::Runtime& runtime,
    std::shared_ptr<Context> context,
    std::shared_ptr<Scheduler> scheduler,
    const AttachOptions& options
  ) {
    auto link = std::make_shared<detail::RuntimeLink>(scheduler);
    auto data = std::make_unique<detail::RuntimeData>(runtime, std::move(context), std::move(scheduler), link);

    if (options.installGlobal) {
      installModulesObject(runtime, options.globalName, std::move(link));
    }
    return std::unique_ptr<AttachedRuntime>(new AttachedRuntime(std::move(data)));
  }

  js::Value AttachedRuntime::module(const std::string_view name) {
    return data_->moduleObject(name);
  }

  std::vector<std::string> AttachedRuntime::moduleNames() const {
    return data_->moduleNames();
  }

  std::shared_ptr<js::HostObject> AttachedRuntime::modulesHost() const {
    return std::make_shared<detail::ModuleHost>(data_->link(), std::nullopt);
  }

  js::Value AttachedRuntime::objectOf(const NativeObject& object) {
    return data_->lookup(object.objectId());
  }

  Context& AttachedRuntime::context() const {
    return *data_->context();
  }

  js::Runtime& AttachedRuntime::runtime() const {
    return data_->runtime();
  }
} // namespace expo::modules::v2::cpp
