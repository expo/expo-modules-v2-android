#pragma once

#include <memory>
#include <optional>
#include <type_traits>
#include <variant>

#include <expo-modules-v2/descriptor/CppType.h>
#include <expo-modules-v2/descriptor/CppTypeVisit.h>

namespace expo::modules::v2 {
  enum class LeafType : int {
    BOOLEAN = static_cast<int>(CppType::BOOLEAN),
    STRING = static_cast<int>(CppType::STRING),
    INT = static_cast<int>(CppType::INT),
    LONG = static_cast<int>(CppType::LONG),
    FLOAT = static_cast<int>(CppType::FLOAT),
    DOUBLE = static_cast<int>(CppType::DOUBLE),
    ANY = static_cast<int>(CppType::ANY),
    DOUBLE_ARRAY = static_cast<int>(CppType::DOUBLE_ARRAY),
    INT_ARRAY = static_cast<int>(CppType::INT_ARRAY),
    LONG_ARRAY = static_cast<int>(CppType::LONG_ARRAY),
    FLOAT_ARRAY = static_cast<int>(CppType::FLOAT_ARRAY),
    BOOLEAN_ARRAY = static_cast<int>(CppType::BOOLEAN_ARRAY),
    BYTE_ARRAY = static_cast<int>(CppType::BYTE_ARRAY),
    JS_VALUE = static_cast<int>(CppType::JS_VALUE),
    JS_OBJECT = static_cast<int>(CppType::JS_OBJECT),
    UNIT = static_cast<int>(CppType::UNIT),
    BOX_INT = static_cast<int>(CppType::BOX_INT),
    BOX_BOOLEAN = static_cast<int>(CppType::BOX_BOOLEAN),
    BOX_LONG = static_cast<int>(CppType::BOX_LONG),
    BOX_FLOAT = static_cast<int>(CppType::BOX_FLOAT),
    BOX_DOUBLE = static_cast<int>(CppType::BOX_DOUBLE),
  };

  [[nodiscard]] ALWAYS_INLINE constexpr CppType toCppType(LeafType type) noexcept {
    return static_cast<CppType>(type);
  }

  class ExpectedType {
  public:
    struct List {
      std::unique_ptr<ExpectedType> element;
    };

    struct Map {
      std::unique_ptr<ExpectedType> value;
    };

    struct Record {
      int schemaId;
    };

    struct SharedObject {
      int classId;
    };

    explicit ExpectedType(LeafType type, bool nullable = false, bool usesBuffer = false);

    // clang-format off
    ExpectedType(ExpectedType&&) noexcept = default;
    ExpectedType& operator=(ExpectedType&&) noexcept = default;
    ExpectedType(const ExpectedType&) = delete;
    ExpectedType& operator=(const ExpectedType&) = delete;

    [[nodiscard]] static ExpectedType list(ExpectedType element, bool nullable = false, bool usesBuffer = false);
    [[nodiscard]] static ExpectedType map(ExpectedType value, bool nullable = false, bool usesBuffer = false);
    [[nodiscard]] static ExpectedType record(int schemaId, bool nullable = false, bool usesBuffer = false);
    [[nodiscard]] static ExpectedType sharedObject(int classId, bool nullable = false);
    // clang-format on

    [[nodiscard]] ExpectedType clone() const;

    template<typename R, typename V>
    R visit(V&& v) const {
      static_assert(std::is_nothrow_move_constructible_v<Shape>);

      switch (shape_.index()) {
        case 0: return visitLeafKind<R>(toCppType(*std::get_if<LeafType>(&shape_)), v);
        case 1: return invokeWith<R, CppType::LIST>(v, *std::get_if<List>(&shape_));
        case 2: return invokeWith<R, CppType::MAP>(v, *std::get_if<Map>(&shape_));
        case 3: return invokeWith<R, CppType::RECORD>(v, *std::get_if<Record>(&shape_));
        default: return invokeWith<R, CppType::SHARED_OBJECT>(v, *std::get_if<SharedObject>(&shape_));
      }
    }

    [[nodiscard]] CppType kind() const noexcept;

    [[nodiscard]] bool nullable() const noexcept;

    [[nodiscard]] bool usesBuffer() const noexcept;

    [[nodiscard]] const ExpectedType& listElement() const;

    [[nodiscard]] const ExpectedType& mapValue() const;

    [[nodiscard]] int recordSchemaId() const;

    [[nodiscard]] int sharedClassId() const;

    std::string jniDescriptor() const;

    [[nodiscard]] std::string kotlinType() const;

    bool bufferSafe() const;

  private:
    using Shape = std::variant<LeafType, List, Map, Record, SharedObject>;

    explicit ExpectedType(Shape shape, bool nullable, bool usesBuffer);

    Shape shape_;
    bool nullable_ = false;
    bool usesBuffer_ = false;
  };
} // namespace expo::modules::v2
