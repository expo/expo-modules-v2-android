#pragma once

#include <memory>
#include <string>

#include <react/renderer/components/view/ConcreteViewShadowNode.h>
#include <react/renderer/componentregistry/ComponentDescriptorProvider.h>
#include <react/renderer/core/ConcreteComponentDescriptor.h>

#include <expo-modules-v2/views/EncodedViewProps.h>
#include <expo-modules-v2/views/ViewPropsCodec.h>

namespace expo::modules::v2::react::views {
  using ::expo::modules::v2::views::EncodedViewProps;
  using ::expo::modules::v2::views::ViewPropsCodec;

  /**
   * The key of the raw props Java gets that carries the handle of the view's encoded props. Must stay
   * in sync with `ExpoViewManager.ENCODED_PROPS_KEY`.
   */
  inline constexpr const char* kEncodedPropsKey = "__expoViewProps";

  /**
   * PROTOTYPE. What tells the descriptors of different view types apart. Fabric uses `name.c_str()`
   * as the component handle, so a flavor must stay alive, at the same address, for as long as views
   * of its type can exist.
   */
  struct ExpoViewFlavor {
    std::string name;
    std::shared_ptr<const ViewPropsCodec> codec;
  };

  /**
   * The props of a view of Expo Modules v2: the base view props React Native parses (layout, style,
   * accessibility), and the view's own props encoded in the bridge's binary format.
   *
   * The props own their encoded props, so the handle they send Java resolves for exactly as long as
   * Fabric keeps them.
   */
  class ExpoViewProps final : public facebook::react::ViewProps {
  public:
    ExpoViewProps() = default;

    /** Parses only the base view props and keeps the encoded props of [sourceProps]. */
    ExpoViewProps(
      const facebook::react::PropsParserContext& context,
      const ExpoViewProps& sourceProps,
      const facebook::react::RawProps& rawProps
    )
      : facebook::react::ViewProps(context, sourceProps, rawProps), encoded(sourceProps.encoded) {}

    /** Null until a prop of the view is set: the view then has the decoder's defaults. */
    EncodedViewProps::Shared encoded;
  };

  extern const char ExpoViewComponentName[];

  using ExpoViewShadowNode = facebook::react::ConcreteViewShadowNode<ExpoViewComponentName, ExpoViewProps>;

  /**
   * PROTOTYPE. The component descriptor of the views of Expo Modules v2, one instance per view type,
   * told apart by an `ExpoViewFlavor`. It has no state: the view's own props travel with the props.
   */
  class ExpoViewComponentDescriptor final
    : public facebook::react::ConcreteComponentDescriptor<ExpoViewShadowNode> {
  public:
    explicit ExpoViewComponentDescriptor(const facebook::react::ComponentDescriptorParameters& parameters);

    static facebook::react::ComponentDescriptorProvider provider(std::shared_ptr<const ExpoViewFlavor> flavor);

    facebook::react::ComponentHandle getComponentHandle() const override;

    facebook::react::ComponentName getComponentName() const override;

    /**
     * Parses the base view props as React Native would, encodes the view's own props, and leaves only
     * the handle of the encoded props in the raw props Java gets.
     */
    facebook::react::Props::Shared cloneProps(
      const facebook::react::PropsParserContext& context,
      const facebook::react::Props::Shared& props,
      facebook::react::RawProps rawProps
    ) const override;

  private:
    const ExpoViewFlavor& flavor() const;
  };
} // namespace expo::modules::v2::react::views
