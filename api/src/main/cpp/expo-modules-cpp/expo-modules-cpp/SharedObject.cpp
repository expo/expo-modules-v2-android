#include <expo-modules-cpp/SharedObject.h>

#include <exception>

#include <expo-modules-cpp/Event.h>
#include <expo-modules-cpp/internal/Log.h>

namespace expo::modules::v2::cpp {
  SharedObject::~SharedObject() = default;

  void SharedObject::release() {
    if (released_.exchange(true, std::memory_order_acq_rel)) {
      return;
    }

    try {
      didRelease();
    } catch (const std::exception& error) {
      detail::logError(std::string("didRelease failed: ") + error.what());
    } catch (...) {
      detail::logError("didRelease failed");
    }

    // Nobody can listen to a released object, and nothing will ever remove the listeners it had.
    for (detail::EventBase* const event: events()) {
      event->detachAll();
    }
  }
} // namespace expo::modules::v2::cpp
