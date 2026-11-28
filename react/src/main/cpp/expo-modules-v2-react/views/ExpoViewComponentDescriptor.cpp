#include "ExpoViewComponentDescriptor.h"

#include <exception>
#include <stdexcept>
#include <utility>

#include <expo-modules-v2/utils/Log.h>

#include "RawPropsAccess.h"

namespace jsi = facebook::jsi;
namespace rn = facebook::react;

namespace expo::modules::v2::react::views {
  const char ExpoViewComponentName[] = "ExpoModulesV2View";

  ExpoViewComponentDescriptor::ExpoViewComponentDescriptor(const rn::ComponentDescriptorParameters& parameters)
    : ConcreteComponentDescriptor(parameters) {
    // `react_native_assert` is compiled out of release builds, where a missing flavor would crash
    // later on a null pointer.
    if (flavor_ == nullptr || flavor().codec == nullptr) {
      throw std::invalid_argument("ExpoViewComponentDescriptor requires an ExpoViewFlavor with a codec");
    }
  }

  rn::ComponentDescriptorProvider ExpoViewComponentDescriptor::provider(std::shared_ptr<const ExpoViewFlavor> flavor) {
    const char* name = flavor->name.c_str();
    return rn::ComponentDescriptorProvider{
      .handle = reinterpret_cast<rn::ComponentHandle>(name),
      .name = name,
      .flavor = std::move(flavor),
      .constructor = &rn::concreteComponentDescriptorConstructor<ExpoViewComponentDescriptor>,
    };
  }

  rn::ComponentHandle ExpoViewComponentDescriptor::getComponentHandle() const {
    return reinterpret_cast<rn::ComponentHandle>(getComponentName());
  }

  rn::ComponentName ExpoViewComponentDescriptor::getComponentName() const {
    return flavor().name.c_str();
  }

  rn::Props::Shared ExpoViewComponentDescriptor::cloneProps(
    const rn::PropsParserContext& context,
    const rn::Props::Shared& props,
    rn::RawProps rawProps
  ) const {
    if (!props && rawProps.isEmpty()) {
      return ExpoViewShadowNode::defaultSharedProps();
    }
    const auto& sourceProps = props
      ? static_cast<const ExpoViewProps&>(*props)
      : *ExpoViewShadowNode::defaultSharedProps();
    const ViewPropsCodec& codec = *flavor().codec;

    // The classic path: parses the base view props with the parser that the constructor of
    // `ConcreteComponentDescriptor` prepared. The iterator-setter path would save the parse, once
    // the view's own props are picked out in the same walk (as iOS does since 0.87).
    rawProps.parse(rawPropsParser_);
    auto newProps = std::make_shared<ExpoViewProps>(context, sourceProps, rawProps);

    if (const auto jsiProps = jsiRawProps(rawProps)) {
      jsi::Runtime& rt = jsiProps->runtime;
      try {
        // Null when none of the view's props was set or encoded: the copy keeps the encoded props of
        // `sourceProps`.
        if (auto encoded = codec.encodeObject(rt, jsiProps->value.asObject(rt), sourceProps.encoded)) {
          newProps->encoded = std::move(encoded);
        }
      } catch (const std::exception& error) {
        logLine(std::string("Props of '") + flavor().name + "' were not encoded: " + error.what());
      }
    }
    // PROTOTYPE: props that come as `folly::dynamic`, with no runtime (Reanimated, `setNativeProps`,
    // animations), aren't encoded yet and keep the encoded props of `sourceProps`. iOS converts them
    // back to JSI values in a runtime it can use on the current thread.

#ifdef RN_SERIALIZABLE_STATE
    // What Java gets as the view's `ReactStylesDiffMap`: the base view props, and in place of the
    // view's own props the handle of their encoded form. They are never turned into `folly::dynamic`
    // and then into a `ReadableNativeMap`.
    newProps->initializeDynamicProps(sourceProps, rawProps, [&codec](const std::string& key) {
      return codec.indexOf(key).has_value();
    });
    // Only when they changed: a commit that leaves them alone shares them with `sourceProps`, so the
    // handle the view mounted last keeps resolving.
    // As a double: `ReadableNativeMap` turns every number into one, `INT64` included. Handles count
    // up from 1, so they stay exact up to 2^53.
    if (newProps->encoded && newProps->encoded != sourceProps.encoded) {
      newProps->rawProps[kEncodedPropsKey] = static_cast<double>(newProps->encoded->handle());
    }
#endif
    return newProps;
  }

  const ExpoViewFlavor& ExpoViewComponentDescriptor::flavor() const {
    return *std::static_pointer_cast<const ExpoViewFlavor>(flavor_);
  }
} // namespace expo::modules::v2::react::views
