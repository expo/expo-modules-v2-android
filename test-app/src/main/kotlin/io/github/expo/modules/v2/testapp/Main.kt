package io.github.expo.modules.v2.testapp

import io.github.expo.modules.v2.Buffer
import io.github.expo.modules.v2.BufferMode
import io.github.expo.modules.v2.ExpoModule
import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.Record
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import io.github.expo.modules.v2.testsupport.TestSupport
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeDescriptor
import java.net.URL
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

// Interning a record is eager and native-free: this initializer runs before main() loads the
// native library, and the schema is pushed to native when ensureLoaded() flushes the queue.
private val eagerRecordCodes = AnyType(TypeDescriptor.Simple(MyRecord::class.java, false)).codes

fun main() {
  // Wire kolibri's native bindings to our .dylib before the first native-backed class is touched.
  ExpoHermes.ensureLoaded()

  // Force an explicit exit code so a failed assertion fails the process fast (and a clean run
  // exits 0 promptly rather than waiting on the JSI handle Cleaner's daemon thread).
  try {
    run()
  } catch (t: Throwable) {
    System.err.println("=== FAILED ===")
    t.printStackTrace()
    exitProcess(1)
  }
  exitProcess(0)
}

private fun run() {
  println("=== Expo Modules API v2 — Hermes runtime smoke test ===")

  // The two hand-written registrations left in this file (sections 8 and 9) still name their types
  // through descriptors; every @ExpoModule has them generated instead.
  val intType = AnyType(TypeDescriptor.Int)

  HermesRuntime().use { runtime ->
    // Sections 5-6 drive the :test-support round-trip hooks (globalThis.ExpoTestSupport).
    TestSupport.install(runtime)

    // 1. Plain arithmetic — proves the VM compiles & executes JS.
    val sum = runtime.evaluate("const a = 40, b = 2; a + b;").getInt()
    check(sum == 42) { "expected 42, got $sum" }
    println("40 + 2 = $sum")

    // 2. The native-installed Expo Modules host object.
    val apiVersion = runtime.evaluate("globalThis.ExpoModulesCore.apiVersion").getString()
    val engine = runtime.evaluate("ExpoModulesCore.engine").getString()
    println("ExpoModulesCore.apiVersion = $apiVersion (engine: $engine)")

    // 3. A JS -> native callback through the host function.
    runtime.evaluate("ExpoModulesCore.nativeLog('hello from JavaScript')")

    // 4. A slightly larger script using the host bridge.
    val greeting = runtime.evaluate(
      """
      function describe() {
        return 'Running on ' + ExpoModulesCore.engine +
               ', Expo Modules API v' + ExpoModulesCore.apiVersion;
      }
      describe();
      """.trimIndent(),
    ).getString()
    println("script result = $greeting")

    // 5. Bidirectional JSI <-> JNI type conversion. `__convertRoundTrip(value, tag)`
    // converts the value into the Java type described by `type`, then back into
    // JS; comparing JSON.stringify(out) proves both directions preserve the value.
    // `type` is a pre-order list of CppType codes, exactly what the native side
    // would hand the bridge.
    fun roundTrip(expr: String, type: AnyType): String {
      // TypeCodes renders as `[6, 19]`, which is already a JS array literal.
      val codes = type.codes
      return runtime.evaluate(
        "JSON.stringify(ExpoTestSupport.__convertRoundTrip($expr, $codes))",
      ).getString()
    }

    data class Case(val expr: String, val type: AnyType, val expected: String)

    val conversions = listOf(
      Case("true", AnyType(TypeDescriptor.Bool), "true"),
      Case("42", AnyType(TypeDescriptor.Int), "42"),
      Case("42", AnyType(TypeDescriptor.Long), "42"),
      Case("3.5", AnyType(TypeDescriptor.Double), "3.5"),
      Case("3.5", AnyType(TypeDescriptor.Float), "3.5"),
      Case("'hello'", AnyType(TypeDescriptor.Simple(String::class.java, false)), "\"hello\""),
      Case(
        "[1, 2, 3]",
        AnyType(
          TypeDescriptor.Parametrized(
            List::class.java,
            false,
            arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
          ),
        ),
        "[1,2,3]",
      ),
      Case(
        "{ a: 1, b: 2 }",
        AnyType(
          TypeDescriptor.Parametrized(
            Map::class.java,
            false,
            arrayOf(
              TypeDescriptor.Simple(String::class.java, false),
              TypeDescriptor.Simple(Double::class.javaObjectType, false),
            ),
          ),
        ),
        """{"a":1,"b":2}""",
      ),
      Case(
        "[[1], [2, 3]]",
        AnyType(
          TypeDescriptor.Parametrized(
            List::class.java,
            false,
            arrayOf(
              TypeDescriptor.Parametrized(
                List::class.java,
                false,
                arrayOf(TypeDescriptor.Simple(Int::class.javaObjectType, false)),
              ),
            ),
          ),
        ),
        "[[1],[2,3]]",
      ),
      Case(
        "{ nested: [true, false] }",
        AnyType(
          TypeDescriptor.Parametrized(
            Map::class.java,
            false,
            arrayOf(
              TypeDescriptor.Simple(String::class.java, false),
              TypeDescriptor.Parametrized(
                List::class.java,
                false,
                arrayOf(TypeDescriptor.Simple(Boolean::class.javaObjectType, false)),
              ),
            ),
          ),
        ),
        """{"nested":[true,false]}""",
      ),
      Case("null", AnyType(TypeDescriptor.Simple(Int::class.javaObjectType, true)), "null"),
    )
    for ((expr, type, expected) in conversions) {
      val actual = roundTrip(expr, type)
      check(actual == expected) { "round trip $expr as $type: expected $expected, got $actual" }
      println("roundTrip($expr, $type) = $actual")
    }

    // A shape mismatch (Int expected, string given) must throw a JS error.
    val threw = runtime.evaluate(
      """
      (function () {
        try {
          ExpoTestSupport.__convertRoundTrip('not a number', [${CppType.INT.code}]);
          return 'no-throw';
        } catch (e) {
          return 'threw';
        }
      })();
      """.trimIndent(),
    ).getString()
    check(threw == "threw") { "expected a thrown error for Int/string mismatch, got $threw" }
    println("mismatch correctly threw")

    // 6. Compile-time-typed converters (JSIConverters.h): __nativeRoundTrip(value, kind)
    // extracts arg 0 as a specific native C++ type (fromJSIValue<T>) and re-emits it
    // (toJSIValue). `kind` selects the C++ instantiation.
    fun nativeRoundTrip(expr: String, kind: Int): String =
      runtime.evaluate(
        "JSON.stringify(ExpoTestSupport.__nativeRoundTrip($expr, $kind))",
      ).getString()

    // kind: 0=bool 1=int32 2=int64 3=float 4=double 5=string 6=vector<int>
    //       7=vector<vector<int>>
    val nativeCases = listOf(
      Triple("true", 0, "true"),
      Triple("42", 1, "42"),
      Triple("42", 2, "42"),
      Triple("3.5", 3, "3.5"),
      Triple("3.5", 4, "3.5"),
      Triple("'hello'", 5, "\"hello\""),
      Triple("[1, 2, 3]", 6, "[1,2,3]"),
      Triple("[[1], [2, 3]]", 7, "[[1],[2,3]]"),
    )
    for ((expr, kind, expected) in nativeCases) {
      val actual = nativeRoundTrip(expr, kind)
      check(actual == expected) { "native round trip $expr (kind $kind): expected $expected, got $actual" }
      println("nativeRoundTrip($expr, kind=$kind) = $actual")
    }

    // kind 8 = unordered_map<string,double>; iteration order isn't stable, so
    // assert key/value membership rather than a fixed JSON key order.
    val mapOk = runtime.evaluate(
      """
      (function () {
        const out = ExpoTestSupport.__nativeRoundTrip({ a: 1, b: 2 }, 8);
        return (out.a === 1 && out.b === 2 && Object.keys(out).length === 2) ? 'ok' : JSON.stringify(out);
      })();
      """.trimIndent(),
    ).getString()
    check(mapOk == "ok") { "native round trip map (kind 8): $mapOk" }
    println("nativeRoundTrip({a:1,b:2}, kind=8) = ok")

    // 7. A Kotlin module exported to JS. `moduleRegistry.register` is pure-Kotlin bookkeeping —
    // the module's descriptor (function names + CppType signatures) only crosses JNI, in one
    // binary-buffer up-call, when JS first reads `expo.modules.MathUtils`. That access materializes
    // a plain JS object whose native state owns all export metadata and the Kotlin instance's
    // single global reference. Each exported method resolves separately on its first call.
    val mathUtils = MathUtils()
    runtime.moduleRegistry.register(mathUtils)

    val added = runtime.evaluate("expo.modules.MathUtils.add(40, 2)").getInt()
    check(added == 42) { "expo.modules.MathUtils.add(40, 2): expected 42, got $added" }
    println("expo.modules.MathUtils.add(40, 2) = $added")

    val kotlinGreeting = runtime.evaluate("expo.modules.MathUtils.greet('Expo')").getString()
    check(kotlinGreeting == "Hello, Expo!") { "expo.modules.MathUtils.greet('Expo'): got $kotlinGreeting" }
    println("expo.modules.MathUtils.greet('Expo') = $kotlinGreeting")

    val summed = runtime.evaluate("expo.modules.MathUtils.sum([1, 2, 3, 4])").getInt()
    check(summed == 10) { "expo.modules.MathUtils.sum([1,2,3,4]): expected 10, got $summed" }
    println("expo.modules.MathUtils.sum([1, 2, 3, 4]) = $summed")

    // 7b. Modules materialize lazily but with stable identity: repeated reads return the same
    // cached JS object, and an unregistered name reads as plain `undefined`.
    val identity = runtime.evaluate("expo.modules.MathUtils === expo.modules.MathUtils").getBool()
    check(identity) { "expo.modules identity: expected true, got $identity" }
    val missing = runtime.evaluate("typeof expo.modules.NoSuchModule").getString()
    check(missing == "undefined") { "unregistered module: expected undefined, got $missing" }
    println("expo.modules is lazy, cached, and undefined for unknown names")

    // 8. The same receiver can back several modules — each materialized module owns one global
    // reference and picks its own subset of methods.
    runtime.moduleRegistry.register("Calculator", mathUtils) {
      function("add", intType, intType, returns = intType)
      function("multiply", intType, intType, returns = intType)
    }

    val calc = runtime.evaluate(
      """
      const calc = expo.modules.Calculator;
      calc.add(3, 4) + ':' + calc.multiply(3, 4);
      """.trimIndent(),
    ).getString()
    check(calc == "7:12") { "expo.modules.Calculator: expected 7:12, got $calc" }
    println("expo.modules.Calculator -> add(3,4):multiply(3,4) = $calc")

    // 9. Instance methods carry state. A regular (non-singleton) class instance keeps its fields
    // across calls from JS, proving this is real instance dispatch and not a static call in disguise.
    val counter = Counter()
    runtime.moduleRegistry.register("counter", counter) {
      function("increment", returns = intType)
      function("value", returns = intType)
    }
    val counts = runtime.evaluate(
      "const c = expo.modules.counter; c.increment(); c.increment(); c.value()",
    ).getInt()
    check(counts == 2) { "stateful counter: expected 2, got $counts" }
    println("expo.modules.counter.increment() x2 -> value() = $counts")

    // 10. The JavaScriptObject / JavaScriptValue API (mirrors expo-modules-core): write typed
    // properties from Kotlin, then read them back as JavaScriptValues.
    val global = runtime.global()
    val config = runtime.createObject()
    config.setProperty("name", "Expo")
    config.setProperty("version", 2)
    config.setProperty("enabled", true)
    config.setProperty("ratio", 1.5)
    global.setProperty("config", config)

    // Read a nested property through getProperty() -> JavaScriptValue -> getObject() -> getProperty().
    val name = global.getProperty("config").getObject().getProperty("name")
    check(name.isString() && name.getString() == "Expo") { "config.name: got ${name.kind()}/$name" }
    println("config.name = ${name.getString()} (kind=${name.kind()})")

    // hasProperty / getPropertyNames, and the typed JavaScriptValue accessors. hasProperty's native
    // body takes its `name` as an `unowned_java_ref<jstring>` (a borrowed String handle) rather than a
    // raw jstring, so this call round-trips a String through that parameter end to end.
    check(config.hasProperty("version") && !config.hasProperty("missing")) {
      "hasProperty(unowned_java_ref<jstring>) round trip failed"
    }
    println("hasProperty(\"version\")=${config.hasProperty("version")}, hasProperty(\"missing\")=${config.hasProperty("missing")}")
    val names = config.getPropertyNames().toSet()
    check(names == setOf("name", "version", "enabled", "ratio")) { "property names: $names" }
    check(config.getProperty("version").getInt() == 2)
    check(config.getProperty("enabled").getBool())
    check(config.getProperty("ratio").getDouble() == 1.5)
    println("config keys = $names, version=${config.getProperty("version").getInt()}")

    // Read a JS-created array via JavaScriptValue.isArray()/getArray().
    runtime.evaluate("globalThis.nums = [10, 20, 30]")
    val nums = global.getProperty("nums")
    check(nums.isArray()) { "nums.isArray(): ${nums.kind()}" }
    val numValues = nums.getArray()
    check(numValues.size == 3 && numValues[1].getInt() == 20) { "nums: ${numValues.size} elems" }
    println("nums = [${numValues.joinToString(", ") { it.getInt().toString() }}]")

    // 11. Records through a trampoline. `MyModule.record(value: MyRecord)` cannot be invoked
    // directly over JNI (the record bytes need a schema-directed decode first), so the bridge
    // dispatches to the hand-written `record__trampoline` — the shape a compiler plugin will
    // generate — which reads the record from the shared binary buffer, calls the user method,
    // and writes the record result back. One JNI transition per JS call, both directions.
    check(eagerRecordCodes[0] == CppType.RECORD.code) {
      "eager interning: $eagerRecordCodes"
    }
    runtime.moduleRegistry.register(MyModule())

    val echoed = runtime.evaluate(
      "JSON.stringify(expo.modules.MyModule.record({x: 7, b: 'seven'}))",
    ).getString()
    check(echoed == """{"x":7,"b":"seven"}""") { "MyModule.record echo: got $echoed" }
    println("expo.modules.MyModule.record({x: 7, b: 'seven'}) = $echoed")

    val described = runtime.evaluate(
      "expo.modules.MyModule.describe('rec: ', {x: 3, b: 'three'})",
    ).getString()
    check(described == "rec: three/3") { "MyModule.describe: got $described" }
    println("expo.modules.MyModule.describe('rec: ', {x: 3, b: 'three'}) = $described")

    // The payload argument here is the SECOND declared one (prefix takes a JNI slot), and this
    // payload is far too big for the 256 KB buffer. It therefore proves overflow slots are packed in
    // payload order: the record is payload #0, so the cursor finds it in slot 0. Packing it at its
    // declared index 1 would leave slot 0 null and fail.
    val bigTail = runtime.evaluate(
      "expo.modules.MyModule.describe('rec: ', {x: 9, b: 'z'.repeat(300000)}).length",
    ).getInt()
    check(bigTail == "rec: ".length + 300000 + "/9".length) {
      "overflowed describe with a leading JNI-slot argument: got $bigTail"
    }
    println("expo.modules.MyModule.describe('rec: ', {x: 9, b: 300k chars}).length = $bigTail")

    // Two record arguments: their payloads are concatenated in the shared buffer in declared
    // order, and the trampoline reads them back positionally — still one JNI transition.
    val merged = runtime.evaluate(
      "JSON.stringify(expo.modules.MyModule.merge({x: 1, b: 'one'}, {x: 2, b: 'two'}))",
    ).getString()
    check(merged == """{"x":3,"b":"onetwo"}""") { "MyModule.merge: got $merged" }
    println("expo.modules.MyModule.merge({x: 1, b: 'one'}, {x: 2, b: 'two'}) = $merged")

    // 12. Converted types (TypeConverter): the user methods take/return URL and Duration, but
    // JS and the JNI signature only ever see the bridge types (STRING, DOUBLE). The trampoline
    // receives the bridge values and converts on the Kotlin side — no C++ involvement per type.
    runtime.moduleRegistry.register(LinkUtils())

    val host = runtime.evaluate("expo.modules.LinkUtils.host('https://expo.dev/path')").getString()
    check(host == "expo.dev") { "LinkUtils.host: got $host" }
    println("expo.modules.LinkUtils.host('https://expo.dev/path') = $host")

    // 13. Element-wise conversion inside containers.
    runtime.moduleRegistry.register(ConvertedContainers())
    val fileNames = runtime.evaluate(
      "expo.modules.Containers.echoFiles(['/tmp/a.txt', '/tmp/b.txt'])",
    ).getString()
    check(fileNames == "a.txt,b.txt") { "List<File>: got $fileNames" }
    println("expo.modules.Containers.echoFiles(['/tmp/a.txt','/tmp/b.txt']) = $fileNames")

    val withNull = runtime.evaluate(
      "expo.modules.Containers.echoNullableFiles(['/tmp/a.txt', null])",
    ).getString()
    check(withNull == "a.txt,null") { "List<File?>: got $withNull" }
    println("expo.modules.Containers.echoNullableFiles(['/tmp/a.txt',null]) = $withNull")

    val durations = runtime.evaluate("expo.modules.Containers.totalOf({a: 1.5, b: 0.25})").getDouble()
    check(durations == 1.75) { "Map<String, Duration>: got $durations" }
    println("expo.modules.Containers.totalOf({a:1.5,b:0.25}) = $durations (seconds)")

    val made = runtime.evaluate("JSON.stringify(expo.modules.Containers.makeFiles(3))").getString()
    check(made == """["/tmp/f0","/tmp/f1","/tmp/f2"]""") { "List<File> result: got $made" }
    println("expo.modules.Containers.makeFiles(3) = $made")

    // 13b. The overflow branches. The shared buffer is 256 KB, so these payloads cannot fit and
    // the bridge hands each argument over in its object slot instead — element converters then run
    // on the JNI shape rather than on buffer bytes.
    val overflowCount = runtime.evaluate(
      "expo.modules.Containers.countFiles(" +
        "Array.from({length: 8000}, (_, i) => '/tmp/' + String(i).padStart(60, '0') + '.txt'))",
    ).getInt()
    check(overflowCount == 8000) { "List<File> argument overflow: got $overflowCount" }
    println("expo.modules.Containers.countFiles(8000 paths, overflowed) = $overflowCount")

    val overflowNames = runtime.evaluate(
      "expo.modules.Containers.echoFiles(" +
        "Array.from({length: 8000}, (_, i) => '/tmp/' + String(i).padStart(60, '0') + '.txt'))" +
        ".split(',').length",
    ).getInt()
    check(overflowNames == 8000) { "List<File> overflow echo: got $overflowNames" }
    println("expo.modules.Containers.echoFiles(8000 paths, overflowed).split(',').length = $overflowNames")

    // The result direction overflows too: writeResult stashes the bridge shape out of band, which
    // means typedToBridge has to apply File -> String per element.
    val bigResult = runtime.evaluate("expo.modules.Containers.makeFiles(30000).length").getInt()
    check(bigResult == 30000) { "List<File> result overflow: got $bigResult" }
    println("expo.modules.Containers.makeFiles(30000).length = $bigResult (result overflowed)")

    val totalSeconds = runtime.evaluate("expo.modules.LinkUtils.total(1.5, 0.25)").getDouble()
    check(totalSeconds == 1.75) { "LinkUtils.total: got $totalSeconds" }
    println("expo.modules.LinkUtils.total(1.5, 0.25) = $totalSeconds (seconds)")

    // 14. The structural containers that are not List/Map: object arrays and sets. Neither is
    // registered — the registry resolves them from the descriptor, exactly as it does a List. An
    // array class already names its element (`URL[]` says URL), so `Array<URL>` needs only its class
    // token; a parameter states what a class cannot, which is what `Array<String?>` needs.
    runtime.moduleRegistry.register(ArrayUtils())

    // The array crosses as its bridge list, so each element is converted in place: URL elements
    // arrive as strings and the trampoline hands the module a real Array<URL>.
    val hosts = runtime.evaluate(
      "expo.modules.ArrayUtils.hosts(['https://expo.dev/a', 'https://docs.expo.dev/b'])",
    ).getString()
    check(hosts == "expo.dev,docs.expo.dev") { "Array<URL>: got $hosts" }
    println("expo.modules.ArrayUtils.hosts(['https://expo.dev/a','https://docs.expo.dev/b']) = $hosts")

    // Element nullability lives in the element type, so one converter class serves Array<String?>.
    val labels = runtime.evaluate("expo.modules.ArrayUtils.labels(['a', null, 'b'])").getString()
    check(labels == "a,null,b") { "Array<String?>: got $labels" }
    println("expo.modules.ArrayUtils.labels(['a',null,'b']) = $labels")

    val madeUrls = runtime.evaluate("JSON.stringify(expo.modules.ArrayUtils.makeUrls(3))").getString()
    check(madeUrls == """["https://expo.dev/0","https://expo.dev/1","https://expo.dev/2"]""") {
      "Array<URL> result: got $madeUrls"
    }
    println("expo.modules.ArrayUtils.makeUrls(3) = $madeUrls")

    // A nullable array declaration: the array itself may be null, and its presence byte gates the
    // whole payload rather than any element.
    val noUrls = runtime.evaluate("expo.modules.ArrayUtils.countUrls(null)").getInt()
    check(noUrls == -1) { "Array<URL>? null: got $noUrls" }
    val oneUrl = runtime.evaluate("expo.modules.ArrayUtils.countUrls(['https://expo.dev/'])").getInt()
    check(oneUrl == 1) { "Array<URL>? present: got $oneUrl" }
    println("expo.modules.ArrayUtils.countUrls(null) = $noUrls, countUrls(['https://expo.dev/']) = $oneUrl")

    // Overflow, both directions: too big for the 256 KB buffer, so the value takes an object slot
    // and the converter's JNI pass (fromBridge / toBridge) runs instead of its buffer pass.
    val overflowUrls = runtime.evaluate(
      "expo.modules.ArrayUtils.countUrls(" +
        "Array.from({length: 8000}, (_, i) => 'https://expo.dev/' + String(i).padStart(60, '0')))",
    ).getInt()
    check(overflowUrls == 8000) { "Array<URL> argument overflow: got $overflowUrls" }
    println("expo.modules.ArrayUtils.countUrls(8000 urls, overflowed) = $overflowUrls")

    val overflowMade = runtime.evaluate("expo.modules.ArrayUtils.makeUrls(30000).length").getInt()
    check(overflowMade == 30000) { "Array<URL> result overflow: got $overflowMade" }
    println("expo.modules.ArrayUtils.makeUrls(30000).length = $overflowMade (result overflowed)")

    // A Set rides the same bridge list as a List, so it buffers.
    runtime.moduleRegistry.register(SetUtils())

    val setHosts = runtime.evaluate(
      "expo.modules.SetUtils.hostsOf(" +
        "['https://expo.dev/a', 'https://expo.dev/a', 'https://docs.expo.dev/b'])",
    ).getString()
    check(setHosts == "expo.dev,docs.expo.dev") { "Set<URL>: got $setHosts" }
    println("expo.modules.SetUtils.hostsOf(['…/a','…/a','docs…/b']) = $setHosts (deduplicated)")

    // 15. Optional record fields. A field declared with `hasDefault = true` keeps its own type —
    // `count` is still INT — and gains one inbound presence byte, so native can tell a missing or
    // `undefined` JS property apart from an explicit null. Absent means the codec applies the
    // Kotlin default; null stays null. Nothing is written the other way round, because Kotlin
    // always has a value for every field.
    runtime.moduleRegistry.register(DefaultsUtils())

    for (name in listOf("describe", "describeMap")) {
      val present = runtime.evaluate(
        "expo.modules.DefaultsUtils.$name({tag: 't', count: 1, label: 'l', trailing: 2})",
      ).getString()
      check(present == "t/1/l/2") { "$name all fields present: got $present" }

      // Missing and `undefined` both take the defaults; the field after them must not shift.
      val absent = runtime.evaluate("expo.modules.DefaultsUtils.$name({tag: 't'})").getString()
      check(absent == "t/5/z/7") { "$name optional fields absent: got $absent" }
      val undefined = runtime.evaluate(
        "expo.modules.DefaultsUtils.$name({tag: 't', count: undefined, trailing: 3})",
      ).getString()
      check(undefined == "t/5/z/3") { "$name undefined optional field: got $undefined" }

      // Explicit null on a nullable optional field is not absence, so the default is not applied.
      val explicitNull = runtime.evaluate(
        "expo.modules.DefaultsUtils.$name({tag: 't', label: null})",
      ).getString()
      check(explicitNull == "t/5/null/7") { "$name explicit null: got $explicitNull" }

      println(
        "expo.modules.DefaultsUtils.$name: present = $present, absent = $absent, " +
          "undefined = $undefined, null = $explicitNull",
      )
    }

    // 16. Async prerequisites (phase 0 spike). Two facts the async design rests on, neither of
    // which was ever exercised before: this Hermes build ships a real `Promise`, and its microtask
    // queue only runs when something drains it.
    val promiseKind = runtime.evaluate("typeof Promise").getString()
    check(promiseKind == "function") { "Hermes has no built-in Promise: typeof Promise = $promiseKind" }

    runtime.evaluate(
      """
      globalThis.hit = false;
      Promise.resolve('settled').then((v) => { globalThis.hit = v; });
      """.trimIndent(),
    )
    // Nothing drains implicitly, so the callback has not run yet.
    check(!runtime.evaluate("globalThis.hit !== false").getBool()) {
      "a microtask ran without an explicit drain"
    }
    val empty = runtime.drainMicrotasks()
    val hit = runtime.evaluate("globalThis.hit").getString()
    check(hit == "settled") { "drainMicrotasks did not run the .then callback: got $hit" }
    println("typeof Promise = $promiseKind, drainMicrotasks() = $empty, .then fired = $hit")

    // 17. Async exports. `@JS` on a `suspend` function generates the whole thing: the bridge hands
    // the trampoline a `Promise`, the trampoline starts a coroutine, and JavaScript gets a real
    // Promise back straight away.
    runtime.moduleRegistry.register(AsyncModule())

    // A Promise arrives immediately, before any Kotlin result exists.
    val promiseType = runtime.evaluate(
      "globalThis.p = expo.modules.Async.immediate(1); Object.prototype.toString.call(globalThis.p)",
    ).getString()
    check(promiseType == "[object Promise]") { "expected a Promise, got $promiseType" }

    // Nothing settles until the JS thread runs its jobs, so `.then` has not fired yet.
    runtime.evaluate("globalThis.out = null; globalThis.p.then((v) => { globalThis.out = v; });")
    check(runtime.evaluate("globalThis.out === null").getBool()) {
      "an async export settled without a drain"
    }

    runtime.runEventLoop { !runtime.evaluate("globalThis.out === null").getBool() }
    val immediate = runtime.evaluate("globalThis.out").getString()
    check(immediate == "immediate:1") { "immediate: got $immediate" }
    println("expo.modules.Async.immediate(1) -> $promiseType -> $immediate")

    // A real suspension resumes off the JS thread; the settle still lands on it.
    runtime.evaluate(
      "globalThis.slow = null; expo.modules.Async.delayed(2).then((v) => { globalThis.slow = v; });",
    )
    runtime.runEventLoop { !runtime.evaluate("globalThis.slow === null").getBool() }
    val delayed = runtime.evaluate("globalThis.slow").getString()
    check(delayed == "delayed:2") { "delayed: got $delayed" }
    println("expo.modules.Async.delayed(2) -> $delayed (resumed off the JS thread)")

    // A thrown exception becomes a rejection carrying the code and the message.
    runtime.evaluate(
      "globalThis.err = null;" +
        "expo.modules.Async.failing().catch((e) => { globalThis.err = e.code + '/' + e.message; });",
    )
    runtime.runEventLoop { !runtime.evaluate("globalThis.err === null").getBool() }
    val rejected = runtime.evaluate("globalThis.err").getString()
    check(rejected == "java.lang.IllegalStateException/nope") { "rejection: got $rejected" }
    println("expo.modules.Async.failing() rejected with $rejected")

    // A buffered argument decoded eagerly, and a scalar result that crosses in a boxed JNI slot.
    runtime.evaluate(
      "globalThis.sum = null; expo.modules.Async.sum([1, 2, 3, 4]).then((v) => { globalThis.sum = v; });",
    )
    runtime.runEventLoop { !runtime.evaluate("globalThis.sum === null").getBool() }
    val summedAsync = runtime.evaluate("globalThis.sum").getInt()
    check(summedAsync == 10) { "async sum: got $summedAsync" }

    // A Unit body resolves with undefined rather than staying pending.
    runtime.evaluate(
      "globalThis.done = null; expo.modules.Async.record().then((v) => { globalThis.done = (v === undefined); });",
    )
    runtime.runEventLoop { !runtime.evaluate("globalThis.done === null").getBool() }
    check(runtime.evaluate("globalThis.done").getBool()) { "a Unit async export did not resolve with undefined" }
    println("expo.modules.Async.sum([1,2,3,4]) = $summedAsync, record() resolved with undefined")
  }

  println("=== OK ===")
}

/**
 * A plain Kotlin class whose methods are exported to JS in [run] (sections 7 & 8).
 *
 * `@JS` generates the whole registration — every argument and return type, the transport for each
 * value, and the trampolines. Section 8 registers this same object a second time through the
 * hand-written DSL, which is how one receiver still backs two module names.
 */
@ExpoModule
class MathUtils : Module() {
  val answer = 42

  @JS
  fun add(a: Int, b: Int): Int = a + b

  // The String argument and result both ride the buffer by default, so the plugin generates a
  // trampoline for this one and the bridge never sees a jstring.
  @JS
  fun greet(name: String): String = "Hello, $name!"

  // A typed List always rides the payload — 8-14x faster than a JList slot of boxed elements.
  @JS
  fun sum(values: List<Int>): Int = values.sum()

  @JS
  fun multiply(a: Int, b: Int): Int = a * b
}

/** A record crossing the bridge as a typed positional payload in [run] (section 11). */
@Record
data class MyRecord(val x: Int, val b: String)

/**
 * A module taking and returning records in [run] (section 11). `@JS` generates the trampolines that
 * decode each record off the payload and write the result back; see
 * [io.github.expo.modules.v2.args.Trampoline] for the contract they follow.
 */
@ExpoModule
class MyModule : Module() {
  @JS
  fun record(value: MyRecord): MyRecord = value

  // `prefix` is pinned to a JNI slot so this stays the mixed case: one slot argument ahead of one
  // payload argument, which is what proves overflow slots are packed in payload order.
  @JS
  fun describe(
    @BufferMode(Buffer.NO) prefix: String,
    value: MyRecord,
  ): String = "$prefix${value.b}/${value.x}"

  @JS
  fun merge(a: MyRecord, b: MyRecord): MyRecord = MyRecord(a.x + b.x, a.b + b.b)
}

/**
 * A module using converted types ([io.github.expo.modules.v2.converters.TypeConverter]) in [run] (section 12).
 * Only the bridge type (String, Double) ever reaches JS and the JNI signature; the generated
 * trampolines convert on the Kotlin side.
 *
 * `Duration` bridges as an unboxed `Double`, which the buffer cannot carry, so [total] keeps its JNI
 * slots with no override. [host] is pinned to a slot to keep exercising that path for a `String`
 * bridge, which the buffer would otherwise win.
 */
@ExpoModule
class LinkUtils : Module() {
  @JS
  @BufferMode(Buffer.NO)
  fun host(url: URL): String = url.host

  @JS
  fun total(a: Duration, b: Duration): Duration = a + b
}

/** Element-wise conversion inside containers: List<File>, List<File?>, Map<String, Duration>. */
@ExpoModule(name = "Containers")
class ConvertedContainers : Module() {
  @JS
  fun echoFiles(values: List<java.io.File>): String = values.joinToString(",") { it.name }

  @JS
  fun echoNullableFiles(values: List<java.io.File?>): String =
    values.joinToString(",") { it?.name ?: "null" }

  @JS
  fun totalOf(values: Map<String, Duration>): Double =
    values.values.fold(Duration.ZERO) { a, b -> a + b }.inWholeMilliseconds / 1000.0

  @JS
  fun countFiles(values: List<java.io.File>): Int = values.count { it.name.isNotEmpty() }

  /** The return direction: List<File> encoded back out through the same converters. */
  @JS
  fun makeFiles(count: Int): List<java.io.File> = List(count) { java.io.File("/tmp/f$it") }
}

/**
 * Object arrays through [io.github.expo.modules.v2.converters.ArrayConverter] in [run] (section 14). An array
 * bridges as its element type's list, so these trampolines read and write the array itself while
 * only strings ever reach JS.
 */
@ExpoModule
class ArrayUtils : Module() {
  @JS
  fun hosts(urls: Array<URL>): String = urls.joinToString(",") { it.host }

  @JS
  fun labels(values: Array<String?>): String = values.joinToString(",") { it ?: "null" }

  @JS
  fun makeUrls(count: Int): Array<URL> = Array(count) { URL("https://expo.dev/$it") }

  /** A nullable array declaration — the whole array, not its elements, may be absent. */
  @JS
  fun countUrls(urls: Array<URL>?): Int = urls?.size ?: -1
}

@ExpoModule
class SetUtils : Module() {
  @JS
  fun hostsOf(urls: Set<URL>): String = urls.joinToString(",") { it.host }
}

/**
 * A record with optional fields in [run] (section 15). The defaults are non-null, so absent,
 * `null` and a value are three visibly distinct outcomes; `trailing` sits after the optional
 * fields, which pins the field cursor.
 */
@Record
data class WithDefaults(
  val tag: String,
  val count: Int = 5,
  val label: String? = "z",
  val trailing: Int = 7,
)

/** Both crossing shapes for [WithDefaults] in [run] (section 15): binary payload and `Map`. */
@ExpoModule
class DefaultsUtils : Module() {
  @JS
  fun describe(value: WithDefaults): String =
    "${value.tag}/${value.count}/${value.label}/${value.trailing}"

  /**
   * The same record over the other crossing shape. `Buffer.NO` sends it as a `Map<String, Any?>`
   * instead of a payload, so an absent optional field is a key that is simply not there — which is
   * what makes both halves of the optional-field contract observable from one module.
   */
  @JS(name = "describeMap")
  @BufferMode(Buffer.NO)
  fun describeMapped(value: WithDefaults): String = describe(value)
}

/**
 * The shape `@JS` generates for a `suspend` export, written out by hand.
 *
 * Each trampoline is what the bridge actually calls: it takes the declared arguments plus a
 * trailing [Promise], starts the user's body as a coroutine, and returns nothing. The result
 * crosses when the coroutine finishes, not when the trampoline returns.
 */
@ExpoModule(name = "Async")
class AsyncModule : Module() {
  @JS
  suspend fun immediate(value: Int): String = "immediate:$value"

  @JS
  suspend fun delayed(value: Int): String {
    // A real suspension: this resumes on a coroutine timer thread, not on the JS thread.
    delay(10.milliseconds)
    return "delayed:$value"
  }

  @JS
  suspend fun failing(): String = throw IllegalStateException("nope")

  /** A scalar result: it takes a JNI slot, boxed, rather than the buffer. */
  @JS
  suspend fun sum(values: List<Int>): Int {
    delay(1.milliseconds)
    return values.sum()
  }

  /** Nothing to resolve with: the promise settles with `undefined`. */
  @JS
  suspend fun record(): Unit = delay(1.milliseconds)
}

class Counter : Module() {
  private var count = 0

  fun increment(): Int {
    count += 1
    return count
  }

  fun value(): Int = count
}
