#pragma once

#include <jsi/jsi.h>
#include <functional>
#include <memory>
#include <vector>

namespace expo::jsi {
  /**
   * A function that is responsible for initializing the backed object.
   */
  using LazyObjectInitializer = std::function<std::shared_ptr<facebook::jsi::Object>(facebook::jsi::Runtime&)>;

  /**
   * A host object that defers the creating of the raw object until any property is accessed for
   * the first time.
   */
  class LazyObject : public facebook::jsi::HostObject {
  public:
    explicit LazyObject(LazyObjectInitializer initializer);

    ~LazyObject() override;

    facebook::jsi::Value get(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::PropNameID& name
    ) override;

    void set(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::PropNameID& name,
      const facebook::jsi::Value& value
    ) override;

    std::vector<facebook::jsi::PropNameID> getPropertyNames(facebook::jsi::Runtime& rt) override;

  private:
    const LazyObjectInitializer initializer_;
    std::shared_ptr<facebook::jsi::Object> backedObject_;

    /**
     * Initializes the backed object. It shouldn't be invoked more than once, so first make sure
     * that `backedObject_` is a null pointer.
     */
    void initializeBackedObject(facebook::jsi::Runtime& rt) {
      backedObject_ = initializer_(rt);
    }
  }; // class LazyObject
} // namespace expo::jsi
