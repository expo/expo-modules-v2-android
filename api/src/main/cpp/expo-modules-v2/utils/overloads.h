#pragma once

namespace expo::modules::v2 {
  template<class... Ts>
  struct overloads : Ts... {
    using Ts::operator()...;
  };
}
