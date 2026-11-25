#include <expo-modules-v2/descriptor/ExpectedType.h>
#include <expo-modules-v2/descriptor/FunctionSignature.h>

#include <cassert>
#include <cstdlib>
#include <string>
#include <utility>

#include <kolibri/class.h>
#include <kolibri/array.h>
#include <kolibri/box.h>

#include <expo-modules-v2/utils/overloads.h>
#include <expo-modules-v2/jni/JSharedObject.h>
#include <expo-modules-v2/records/RecordRegistry.h>
#include <expo-modules-v2/sharedobjects/SharedObjectClassRegistry.h>
#include <expo-modules-v2/jni/JUnionValue.h>
#include <expo-modules-v2/unions/UnionSchema.h>
#include <expo-modules-v2/jsi/JavaScriptValue.h>
#include <expo-modules-v2/jsi/JavaScriptFunction.h>
#include <expo-modules-v2/jsi/JavaScriptObject.h>
#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2 {
  namespace jsi {
    class JavaScriptObject;
  }

  namespace {
    bool isScalar(const CppType kind) {
      switch (kind) {
        case CppType::BOOLEAN:
        case CppType::INT:
        case CppType::LONG:
        case CppType::FLOAT:
        case CppType::DOUBLE:
          return true;
        default:
          return false;
      }
    }
  }

  ExpectedType::ExpectedType(
    LeafType type,
    bool nullable,
    bool usesBuffer
  ) : shape_(type),
      kind_(toCppType(type)),
      nullable_(nullable),
      usesBuffer_(usesBuffer) {
    if (nullable && type == LeafType::UNIT) {
      throwInvalidArgument({"A UNIT ExpectedType cannot carry the nullable flag"});
    }

    // A buffered value crosses as an object; a scalar does that only when it is nullable.
    if (
      usesBuffer && (
        isPrimitive() ||
        type == LeafType::UNIT ||
        type == LeafType::JS_VALUE ||
        type == LeafType::JS_OBJECT
      )
    ) {
      throwInvalidArgument({"This leaf ExpectedType cannot ride the binary buffer"});
    }
  }

  ExpectedType ExpectedType::list(ExpectedType element, bool nullable, bool usesBuffer) {
    return ExpectedType(
      List{.element = std::make_unique<ExpectedType>(std::move(element))},
      nullable,
      usesBuffer
    );
  }

  ExpectedType ExpectedType::map(ExpectedType value, bool nullable, bool usesBuffer) {
    return ExpectedType(
      Map{.value = std::make_unique<ExpectedType>(std::move(value))},
      nullable,
      usesBuffer
    );
  }

  ExpectedType ExpectedType::record(int schemaId, bool nullable, bool usesBuffer) {
    return ExpectedType(Record{schemaId}, nullable, usesBuffer);
  }

  ExpectedType ExpectedType::sharedObject(const int classId, const bool nullable) {
    // A reference cannot be flattened, so there is no usesBuffer parameter to pass.
    return ExpectedType(SharedObject{classId}, nullable, /* usesBuffer = */ false);
  }

  ExpectedType ExpectedType::tuple(std::vector<ExpectedType> elements, bool nullable, bool usesBuffer) {
    return ExpectedType(Tuple{std::move(elements)}, nullable, usesBuffer);
  }

  ExpectedType ExpectedType::unionOf(
    std::shared_ptr<const UnionSchema> schema,
    const bool nullable,
    const bool usesBuffer
  ) {
    const UnionSchema* target = schema.get();
    const Union union_{
      .owner = std::move(schema),
      .schema = target
    };

    return ExpectedType(
      std::move(union_),
      nullable,
      usesBuffer
    );
  }

  ExpectedType ExpectedType::unionRef(const UnionSchema* schema, const bool nullable) {
    Union union_{
      .owner = nullptr,
      .schema = schema
    };

    // Nested by construction, so it never carries the buffer flag: that is head-position only.
    return ExpectedType(
      std::move(union_),
      nullable,
      /* usesBuffer = */ false
    );
  }

  ExpectedType ExpectedType::function(FunctionSignature signature, const bool nullable) {
    // A function is a live handle, so it can never ride the binary buffer.
    return ExpectedType(
      Function{
        .signature = std::make_shared<const FunctionSignature>(std::move(signature))
      },
      nullable,
      /* usesBuffer = */ false
    );
  }

  ExpectedType ExpectedType::clone() const {
    return std::visit(
      overloads{
        [this](const LeafType leaf) {
          return ExpectedType(Shape(leaf), nullable_, usesBuffer_);
        },
        [this](const List& list) {
          return ExpectedType(
            List{std::make_unique<ExpectedType>(list.element->clone())},
            nullable_,
            usesBuffer_
          );
        },
        [this](const Map& map) {
          return ExpectedType(
            Map{std::make_unique<ExpectedType>(map.value->clone())},
            nullable_,
            usesBuffer_
          );
        },
        [this](const Record& record) {
          return ExpectedType(Shape(record), nullable_, usesBuffer_);
        },
        [this](const SharedObject& shared) {
          return ExpectedType(Shape(shared), nullable_, usesBuffer_);
        },
        [this](const Tuple& tuple) {
          std::vector<ExpectedType> elements;
          elements.reserve(tuple.elements.size());
          for (const ExpectedType& element: tuple.elements) {
            elements.push_back(element.clone());
          }
          return ExpectedType(Tuple{std::move(elements)}, nullable_, usesBuffer_);
        },
        [this](const Union& union_) {
          return ExpectedType(
            Union{.owner = union_.owner, .schema = union_.schema},
            nullable_,
            usesBuffer_
          );
        },
        [this](const Function& function) {
          return ExpectedType(Shape(function), nullable_, usesBuffer_);
        },
      },
      shape_
    );
  }


  const ExpectedType& ExpectedType::listElement() const {
    return *std::get<List>(shape_).element;
  }

  const ExpectedType& ExpectedType::mapValue() const {
    return *std::get<Map>(shape_).value;
  }

  int ExpectedType::recordSchemaId() const {
    return std::get<Record>(shape_).schemaId;
  }

  int ExpectedType::sharedClassId() const {
    return std::get<SharedObject>(shape_).classId;
  }

  const UnionSchema& ExpectedType::unionSchema() const {
    return *std::get<Union>(shape_).schema;
  }

  template<typename T>
  static std::string desc() {
    return kolibri::jni_descriptor_string_v<T>();
  }

  bool ExpectedType::isPrimitive() const noexcept {
    return !nullable_ && isScalar(kind_);
  }

  std::string ExpectedType::jniDescriptor() const {
    const bool boxed = nullable_;
    return std::visit(
      overloads{
        [boxed](const LeafType leaf) {
          switch (leaf) {
            case LeafType::BOOLEAN:
              return boxed ? desc<kolibri::JBoolean>() : desc<jboolean>();
            case LeafType::INT:
              return boxed ? desc<kolibri::JInteger>() : desc<jint>();
            case LeafType::LONG:
              return boxed ? desc<kolibri::JLong>() : desc<jlong>();
            case LeafType::FLOAT:
              return boxed ? desc<kolibri::JFloat>() : desc<jfloat>();
            case LeafType::DOUBLE:
              return boxed ? desc<kolibri::JDouble>() : desc<jdouble>();
            case LeafType::STRING:
              return desc<jstring>();
            case LeafType::ANY:
              return desc<jobject>();
            case LeafType::DOUBLE_ARRAY:
              return desc<kolibri::JArray<jdouble>>();
            case LeafType::INT_ARRAY:
              return desc<kolibri::JArray<jint>>();
            case LeafType::LONG_ARRAY:
              return desc<kolibri::JArray<jlong>>();
            case LeafType::FLOAT_ARRAY:
              return desc<kolibri::JArray<jfloat>>();
            case LeafType::BOOLEAN_ARRAY:
              return desc<kolibri::JArray<jboolean>>();
            case LeafType::BYTE_ARRAY:
              return desc<kolibri::JArray<jbyte>>();
            case LeafType::JS_VALUE:
              return desc<jsi::JavaScriptValue>();
            case LeafType::JS_OBJECT:
              return desc<jsi::JavaScriptObject>();
            case LeafType::UNIT:
              return desc<kolibri::JUnit>();
          }

          assert(false && "Unsupported type");
          // Every LeafType is handled above; this keeps a release build (no assert) from falling
          // off the end, which GCC rejects under -Werror=return-type.
          std::abort();
        },
        [](const List&) { return desc<kolibri::JList>(); },
        [](const Map&) { return desc<kolibri::JMap>(); },
        [](const Record&) { return desc<kolibri::JMap>(); },
        // The declared class, not the base: an exported member is resolved by signature, and
        // `SharedObject` would match no method at all.
        [](const SharedObject& shared) {
          const std::string& descriptor =
            sharedobjects::SharedObjectClassRegistry::descriptorOf(shared.classId);
          return descriptor.empty() ? desc<JSharedObject>() : descriptor;
        },
        [](const Tuple&) { return desc<kolibri::JList>(); },
        [](const Union&) { return desc<JUnionValue>(); },
        [](const Function&) { return desc<jsi::JavaScriptFunction>(); },
      },
      shape_
    );
  }

  namespace {
    std::string leafKotlinName(const LeafType leaf) {
      switch (leaf) {
        case LeafType::BOOLEAN:
          return "Boolean";
        case LeafType::STRING:
          return "String";
        case LeafType::INT:
          return "Int";
        case LeafType::LONG:
          return "Long";
        case LeafType::FLOAT:
          return "Float";
        case LeafType::DOUBLE:
          return "Double";
        case LeafType::ANY:
          return "Any";
        case LeafType::DOUBLE_ARRAY:
          return "DoubleArray";
        case LeafType::INT_ARRAY:
          return "IntArray";
        case LeafType::LONG_ARRAY:
          return "LongArray";
        case LeafType::FLOAT_ARRAY:
          return "FloatArray";
        case LeafType::BOOLEAN_ARRAY:
          return "BooleanArray";
        case LeafType::BYTE_ARRAY:
          return "ByteArray";
        case LeafType::JS_VALUE:
          return "JavaScriptValue";
        case LeafType::JS_OBJECT:
          return "JavaScriptObject";
        case LeafType::UNIT:
          return "Unit";
      }

      return "<unknown>";
    }

    std::string recordKotlinName(const int schemaId) {
      try {
        return RecordRegistry::get(schemaId).name;
      } catch (const std::exception&) {
        return "<unregistered record #" + std::to_string(schemaId) + ">";
      }
    }

    std::string tupleKotlinName(const ExpectedType::Tuple& tuple) {
      std::string name;
      switch (tuple.elements.size()) {
        case 2:
          name = "Pair<";
          break;
        case 3:
          name = "Triple<";
          break;
        default:
          name = "Tuple<";
          break;
      }

      for (size_t i = 0; i < tuple.elements.size(); i++) {
        if (i != 0) {
          name += ", ";
        }
        name += tuple.elements[i].kotlinType();
      }
      return name + ">";
    }
  } // namespace

  std::string ExpectedType::kotlinType() const {
    std::string type = "";

    if (usesBuffer_) {
      type += "@Buffer ";
    }

    type = std::visit(
      overloads{
        [](const LeafType leaf) { return leafKotlinName(leaf); },
        [](const List& list) { return "List<" + list.element->kotlinType() + ">"; },
        [](const Map& map) { return "Map<String, " + map.value->kotlinType() + ">"; },
        [](const Record& record) { return recordKotlinName(record.schemaId); },
        [](const SharedObject& shared) {
          return sharedobjects::SharedObjectClassRegistry::nameOf(shared.classId);
        },
        [](const Tuple& tuple) { return tupleKotlinName(tuple); },
        [](const Union& union_) { return union_.schema->name; },
        [](const Function& function) {
          const FunctionSignature& signature = *function.signature;
          std::string name = signature.isSuspend ? "suspend (" : "(";
          for (size_t i = 0; i < signature.params.size(); i++) {
            if (i > 0) {
              name += ", ";
            }
            name += signature.params[i].kotlinType();
          }
          return name + ") -> " + signature.returns.kotlinType();
        },
      },
      shape_
    );

    if (nullable_) {
      type += '?';
    }


    return type;
  }

  bool ExpectedType::bufferSafe() const {
    return std::visit(
      overloads{
        [](const LeafType leaf) {
          if (leaf == LeafType::JS_OBJECT || leaf == LeafType::JS_VALUE) {
            return false;
          }

          return true;
        },
        [](const List& list) { return list.element->bufferSafe(); },
        [](const Map& map) { return map.value->bufferSafe(); },
        [](const Record& record) {
          return RecordRegistry::get(record.schemaId).bufferSafe;
        },
        // A reference: it identifies a native object, so there is nothing to flatten.
        [](const SharedObject&) { return false; },
        [](const Tuple& tuple) {
          for (const ExpectedType& element: tuple.elements) {
            if (!element.bufferSafe()) {
              return false;
            }
          }
          return true;
        },
        [](const Union& union_) {
          // A back-reference sits inside the union it points at, whose variants decide.
          if (union_.owner == nullptr) {
            return true;
          }

          return std::ranges::all_of(
            union_.schema->variants,
            [](const UnionVariantSpec& variant) -> bool {
              return variant.type.bufferSafe();
            }
          );
        },
        // A live handle, like a JSI object.
        [](const Function&) { return false; },
      },
      shape_
    );
  }

  CppType ExpectedType::kindOf(const Shape& shape) noexcept {
    return std::visit(
      overloads{
        [](const LeafType leaf) { return toCppType(leaf); },
        [](const List&) { return CppType::LIST; },
        [](const Map&) { return CppType::MAP; },
        [](const Record&) { return CppType::RECORD; },
        [](const SharedObject&) { return CppType::SHARED_OBJECT; },
        [](const Tuple&) { return CppType::TUPLE; },
        [](const Union&) { return CppType::UNION; },
        [](const Function&) { return CppType::FUNCTION; },
      },
      shape
    );
  }

  ExpectedType::ExpectedType(
    Shape shape,
    bool nullable,
    bool usesBuffer
  ) : shape_(std::move(shape)),
      kind_(kindOf(shape_)),
      nullable_(nullable),
      usesBuffer_(usesBuffer) {
  }
}
