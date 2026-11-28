#include "RawPropsAccess.h"

namespace jsi = facebook::jsi;
namespace rn = facebook::react;

namespace {
  /**
   * Binds a pointer to a private member to the tag's `memberPointer` friend. An explicit
   * instantiation is the one place C++ allows naming a private member ([temp.spec.general]).
   */
  template<typename Tag, typename Tag::MemberPointer member>
  struct PrivateMember {
    friend typename Tag::MemberPointer memberPointer(Tag) { return member; }
  };

  struct RawPropsMode {
    using MemberPointer = rn::RawProps::Mode rn::RawProps::*;
    friend MemberPointer memberPointer(RawPropsMode);
  };

  struct RawPropsRuntime {
    using MemberPointer = jsi::Runtime* rn::RawProps::*;
    friend MemberPointer memberPointer(RawPropsRuntime);
  };

  struct RawPropsValue {
    using MemberPointer = jsi::Value rn::RawProps::*;
    friend MemberPointer memberPointer(RawPropsValue);
  };

  template struct PrivateMember<RawPropsMode, &rn::RawProps::mode_>;
  template struct PrivateMember<RawPropsRuntime, &rn::RawProps::runtime_>;
  template struct PrivateMember<RawPropsValue, &rn::RawProps::value_>;
} // namespace

namespace expo::modules::v2::react::views {
  std::optional<JSIRawProps> jsiRawProps(const rn::RawProps& rawProps) {
    if (rawProps.*memberPointer(RawPropsMode{}) != rn::RawProps::Mode::JSI) {
      return std::nullopt;
    }
    return JSIRawProps{
      .runtime = *(rawProps.*memberPointer(RawPropsRuntime{})),
      .value = rawProps.*memberPointer(RawPropsValue{}),
    };
  }
} // namespace expo::modules::v2::react::views
