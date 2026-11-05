package io.github.expo.modules.v2.compiler.typegen

/**
 * Writes the `scan-exports` report: `{ schemaVersion, exports, stats }`, with the field names and
 * the shapes `src/types.ts` in `expo/expo-modules-macros` declares.
 *
 * The compiler plugin has no JSON library on its classpath, and the report is a fixed tree of a few
 * shapes, so it is written by hand. Keys keep the schema's order and a field the schema marks
 * optional is left out when there is nothing to report, so the output reads like the Swift scan's.
 */
internal object SurfaceJson {
  fun report(surface: ExportedSurface, stats: ScanStats): String =
    JsonWriter().apply {
      obj {
        field("schemaVersion", ExportedSurface.SCHEMA_VERSION)
        // Not in the schema, which only Swift's scanner writes today: what wrote this report, so a
        // consumer merging the reports of both platforms can tell them apart.
        field("producer", ExportedSurface.PRODUCER)
        field("exports") { surface(surface) }
        field("stats") {
          obj {
            field("filesScanned", stats.filesScanned)
            field("filesParsed", stats.filesParsed)
            field("durationMs", stats.durationMs)
          }
        }
      }
    }.toString()

  private fun JsonWriter.surface(surface: ExportedSurface) = obj {
    field("modules") { array(surface.modules) { module(it) } }
    field("sharedObjects") { array(surface.sharedObjects) { sharedObject(it) } }
    field("records") { array(surface.records) { record(it) } }
    field("enums") { array(surface.enums) { enumeration(it) } }
    // Kotlin has no counterpart to Swift's `@Union` enums or the `@ExpoView` the scan reports.
    field("unions") { array(emptyList<Unit>()) {} }
    field("views") { array(emptyList<Unit>()) {} }
  }

  private fun JsonWriter.module(module: ExportedModule) = obj {
    field("name", module.name)
    field("jsName", module.jsName)
    field("functions") { array(module.functions) { function(it) } }
    field("properties") { array(module.properties) { property(it) } }
    field("events") { array(module.events) { event(it) } }
    field("classes") {
      array(module.classes) {
        obj {
          field("name", it.name)
          field("jsName", it.jsName)
        }
      }
    }
    field("file", module.file)
    declared(module)
  }

  private fun JsonWriter.sharedObject(sharedObject: ExportedSharedObject) = obj {
    field("name", sharedObject.name)
    field("jsName", sharedObject.jsName)
    sharedObject.initializer?.let { initializer ->
      field("initializer") {
        obj {
          field("parameters") { array(initializer.parameters) { parameter(it) } }
          declared(initializer)
        }
      }
    }
    sharedObject.sharedRefNative?.let { native ->
      field("sharedRef") { obj { field("native") { type(native) } } }
    }
    field("functions") { array(sharedObject.functions) { function(it) } }
    field("properties") { array(sharedObject.properties) { property(it) } }
    field("events") { array(sharedObject.events) { event(it) } }
    field("file", sharedObject.file)
    declared(sharedObject)
  }

  private fun JsonWriter.record(record: ExportedRecord) = obj {
    field("name", record.name)
    field("properties") {
      array(record.properties) { property ->
        obj {
          field("name", property.name)
          field("type") { type(property.type) }
          field("optional", property.optional)
          field("required", property.required)
          declared(property)
        }
      }
    }
    field("file", record.file)
    declared(record)
  }

  private fun JsonWriter.enumeration(enumeration: ExportedEnum) = obj {
    field("name", enumeration.name)
    field("rawType") { type(enumeration.rawType) }
    field("cases") {
      array(enumeration.cases) { case ->
        obj {
          field("name", case.name)
          field("rawValue", case.rawValue)
          platforms()
        }
      }
    }
    field("file", enumeration.file)
    declared(enumeration)
  }

  private fun JsonWriter.function(function: ExportedFunction) = obj {
    field("name", function.name)
    field("jsName", function.jsName)
    field("parameters") { array(function.parameters) { parameter(it) } }
    function.returns?.let { returns -> field("returns") { type(returns) } }
    field("async", function.async)
    // Kotlin declares no checked exceptions, and any function can throw: the bridge rethrows it
    // into JavaScript as an error.
    field("throws", true)
    // A module is one instance and a shared object's members are its instances', so nothing is
    // static from JavaScript's side.
    field("static", false)
    declared(function)
  }

  private fun JsonWriter.property(property: ExportedProperty) = obj {
    field("name", property.name)
    field("jsName", property.jsName)
    field("type") { type(property.type) }
    field("readonly", property.readonly)
    field("static", false)
    declared(property)
  }

  private fun JsonWriter.event(event: ExportedEvent) = obj {
    field("name", event.name)
    field("jsName", event.jsName)
    event.payload?.let { payload -> field("payload") { type(payload) } }
    // An Android event is delivered at once on the JavaScript thread and posted to it from any other,
    // which is closest to the Swift default.
    field("dispatch", "scheduled")
    declared(event)
  }

  private fun JsonWriter.parameter(parameter: ExportedParameter) = obj {
    // Kotlin has no argument labels; the Swift scan reports `_` for an unlabeled parameter.
    field("label", "_")
    field("name", parameter.name)
    field("type") { type(parameter.type) }
    field("optional", parameter.optional)
  }

  private fun JsonWriter.declared(declared: Declared) {
    field("accessLevel", declared.accessLevel)
    platforms()
  }

  private fun JsonWriter.platforms() {
    field("platforms") {
      array(listOf(ExportedSurface.PLATFORM)) { name -> obj { field("name", name) } }
    }
  }

  private fun JsonWriter.type(node: TypeNode): Unit = obj {
    when (node) {
      is TypeNode.Primitive -> {
        field("kind", "primitive")
        typeOf(node)
        field("name", node.name)
      }

      is TypeNode.Optional -> {
        field("kind", "optional")
        typeOf(node)
        field("wrapped") { type(node.wrapped) }
      }

      is TypeNode.ArrayOf -> {
        field("kind", "array")
        typeOf(node)
        field("element") { type(node.element) }
      }

      is TypeNode.Dictionary -> {
        field("kind", "dictionary")
        typeOf(node)
        field("key") { type(node.key) }
        field("value") { type(node.value) }
      }

      is TypeNode.Ref -> {
        field("kind", "ref")
        typeOf(node)
        field("name", node.name)
        node.refKind?.let { field("refKind", it.spelling) }
      }

      is TypeNode.SharedRef -> {
        field("kind", "sharedRef")
        typeOf(node)
        field("native") { type(node.native) }
      }

      is TypeNode.Unknown -> {
        field("kind", "unknown")
        field("text", node.text)
      }
    }
  }

  private fun JsonWriter.typeOf(node: TypeNode) {
    node.jsType?.let { field("typeof", it.spelling) }
  }
}

/** A pretty-printing JSON writer with two-space indentation, enough for [SurfaceJson]. */
internal class JsonWriter {
  private val out = StringBuilder()
  private var depth = 0

  /** Whether the container being written has had an entry yet, one flag per open container. */
  private val hasEntry = ArrayDeque<Boolean>()

  fun obj(body: JsonWriter.() -> Unit) = container('{', '}', body)

  fun <T> array(items: List<T>, item: JsonWriter.(T) -> Unit) = container('[', ']') {
    for (value in items) {
      entry()
      item(value)
    }
  }

  fun field(name: String, value: String) = field(name) { string(value) }

  fun field(name: String, value: Boolean) = field(name) { out.append(value) }

  fun field(name: String, value: Number) = field(name) { out.append(value) }

  fun field(name: String, value: JsonWriter.() -> Unit) {
    entry()
    string(name)
    out.append(": ")
    value()
  }

  override fun toString(): String = out.toString() + "\n"

  private fun container(open: Char, close: Char, body: JsonWriter.() -> Unit) {
    out.append(open)
    depth++
    hasEntry.addLast(false)
    body()
    val empty = !hasEntry.removeLast()
    depth--
    if (!empty) {
      newline()
    }
    out.append(close)
  }

  /** Starts the next entry of the open container: a separator after the first, then the indent. */
  private fun entry() {
    if (hasEntry.last()) {
      out.append(',')
    }
    hasEntry[hasEntry.lastIndex] = true
    newline()
  }

  private fun newline() {
    out.append('\n')
    repeat(depth) { out.append("  ") }
  }

  private fun string(value: String) {
    out.append('"')
    for (char in value) {
      when (char) {
        '"' -> out.append("\\\"")
        '\\' -> out.append("\\\\")
        '\n' -> out.append("\\n")
        '\r' -> out.append("\\r")
        '\t' -> out.append("\\t")
        else -> if (char < ' ') {
          out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
        } else {
          out.append(char)
        }
      }
    }
    out.append('"')
  }
}
