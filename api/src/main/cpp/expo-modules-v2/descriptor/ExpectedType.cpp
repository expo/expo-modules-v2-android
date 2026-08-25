#include <expo-modules-v2/descriptor/ExpectedType.h>

#include <utility>
#include <string>

#include <kolibri/class.h>
#include <kolibri/array.h>
#include <kolibri/box.h>

#include <expo-modules-v2/utils/overloads.h>
#include <expo-modules-v2/records/RecordRegistry.h>
#include <expo-modules-v2/jsi/JavaScriptValue.h>
#include <expo-modules-v2/jsi/JavaScriptObject.h>

namespace expo::modules::v2 {
  namespace jsi {
    class JavaScriptObject;
  }

  namespace {
    bool isUnboxedScalar(LeafType type) {
      switch (type) {
        case LeafType::BOOLEAN:
        case LeafType::INT:
        case LeafType::LONG:
        case LeafType::FLOAT:
        case LeafType::DOUBLE:
        case LeafType::UNIT:
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
      nullable_(nullable),
      usesBuffer_(usesBuffer) {
    if (nullable && isUnboxedScalar(type)) {
      throw std::invalid_argument("Unboxed scalar ExpectedType cannot carry the nullable flag");
    }
    if (usesBuffer && (
          isUnboxedScalar(type) || type == LeafType::ANY ||
          type == LeafType::JS_VALUE || type == LeafType::JS_OBJECT
        )) {
      throw std::invalid_argument("This leaf ExpectedType cannot ride the binary buffer");
    }
  }

  ExpectedType ExpectedType::list(ExpectedType element, bool nullable, bool usesBuffer) {
    return ExpectedType(
      List{std::make_unique<ExpectedType>(std::move(element))},
      nullable,
      usesBuffer
    );
  }

  ExpectedType ExpectedType::map(ExpectedType value, bool nullable, bool usesBuffer) {
    return ExpectedType(
      Map{std::make_unique<ExpectedType>(std::move(value))},
      nullable,
      usesBuffer
    );
  }

  ExpectedType ExpectedType::record(int schemaId, bool nullable, bool usesBuffer) {
    return ExpectedType(Record{schemaId}, nullable, usesBuffer);
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
      },
      shape_
    );
  }

  CppType ExpectedType::kind() const noexcept {
    return std::visit(
      overloads{
        [](const LeafType leaf) { return toCppType(leaf); },
        [](const List&) { return CppType::LIST; },
        [](const Map&) { return CppType::MAP; },
        [](const Record&) { return CppType::RECORD; },
      },
      shape_
    );
  }

  bool ExpectedType::nullable() const noexcept {
    return nullable_;
  }

  bool ExpectedType::usesBuffer() const noexcept {
    return usesBuffer_;
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

  template<typename T>
  static std::string desc() {
    return kolibri::jni_descriptor_string_v<T>();
  }

  std::string ExpectedType::jniDescriptor() const {
    return std::visit(
      overloads{
        [](const LeafType leaf) {
          switch (leaf) {
            case LeafType::BOOLEAN:
              return desc<jboolean>();
            case LeafType::INT:
              return desc<jint>();
            case LeafType::LONG:
              return desc<jlong>();
            case LeafType::FLOAT:
              return desc<jfloat>();
            case LeafType::DOUBLE:
              return desc<jdouble>();
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
            case LeafType::BOX_INT:
              return desc<kolibri::JInteger>();
            case LeafType::BOX_BOOLEAN:
              return desc<kolibri::JBoolean>();
            case LeafType::BOX_LONG:
              return desc<kolibri::JLong>();
            case LeafType::BOX_FLOAT:
              return desc<kolibri::JFloat>();
            case LeafType::BOX_DOUBLE:
              return desc<kolibri::JDouble>();
          }

          assert(false && "Unsupported type");
        },
        [](const List&) { return desc<kolibri::JList>(); },
        [](const Map&) { return desc<kolibri::JMap>(); },
        [](const Record&) { return desc<kolibri::JMap>(); },
      },
      shape_
    );
  }

  namespace {
    std::string leafKotlinName(const LeafType leaf) {
      switch (leaf) {
        case LeafType::BOOLEAN:
        case LeafType::BOX_BOOLEAN:
          return "Boolean";
        case LeafType::STRING:
          return "String";
        case LeafType::INT:
        case LeafType::BOX_INT:
          return "Int";
        case LeafType::LONG:
        case LeafType::BOX_LONG:
          return "Long";
        case LeafType::FLOAT:
        case LeafType::BOX_FLOAT:
          return "Float";
        case LeafType::DOUBLE:
        case LeafType::BOX_DOUBLE:
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
          if (leaf == LeafType::ANY || leaf == LeafType::JS_OBJECT || leaf == LeafType::JS_VALUE) {
            return false;
          }

          return true;
        },
        [](const List& list) { return list.element->bufferSafe(); },
        [](const Map& map) { return map.value->bufferSafe(); },
        [](const Record& record) {
          return RecordRegistry::get(record.schemaId).bufferSafe;
        }
      },
      shape_
    );
  }

  ExpectedType::ExpectedType(
    Shape shape,
    bool nullable,
    bool usesBuffer
  ) : shape_(std::move(shape)),
      nullable_(nullable),
      usesBuffer_(usesBuffer) {
  }
}
