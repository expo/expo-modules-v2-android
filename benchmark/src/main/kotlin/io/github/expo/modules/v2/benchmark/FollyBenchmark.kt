package io.github.expo.modules.v2.benchmark

import io.github.expo.modules.v2.JS
import io.github.expo.modules.v2.testsupport.ExpoHermes
import io.github.expo.modules.v2.testsupport.HermesRuntime
import io.github.expo.modules.v2.testsupport.TestSupport

/**
 * Compares the three ways a dynamic value can travel from JS to Kotlin, on payloads modeled
 * after REAL Expo module traffic (shapes and sizes taken from a survey of the Expo SDK's
 * Android modules — see the case list), timed C++-side on identical `jsi::Value`s:
 *
 * C++ representation round trip (`jsi::Value` -> repr -> `jsi::Value`):
 *  - `folly` — folly::dynamic (what TurboModules do around a dynamic argument/result);
 *  - `folly+JSON` — the classic-bridge flavor: the dynamic additionally serializes through a
 *    JSON string (folly::toJson / parseJson);
 *  - `binary` — the binary buffer codec (dynamic/tagged flavor).
 *
 * Full delivery to Kotlin-consumable Java objects (HashMap/ArrayList/boxed):
 *  - `folly->Java` — folly::dynamic + manual per-field JNI mapping, i.e. what
 *    ReadableNativeMap.toHashMap() amounts to;
 *  - `cpp->Java` — Java objects built per-field in C++ straight from JSI, no intermediate
 *    representation (our object-slot path: dynamic values and buffer overflows).
 *
 * Case sizing (from the Expo survey): the median module call carries <=~200 B of complex
 * payload (an options record); p95 <=~8 KB (default list pages); the realistic heavy tail is
 * 30-100+ KB (large MediaLibrary/Contacts/Calendar pages, SQLite result sets).
 *
 * Requires a native build configured with `-DEXPO_FOLLY_BENCHMARK=ON` (brew install folly).
 */

private class FollyCase(val name: String, val payloadExpr: String, val iters: Int)

// Shared payload builders, modeled on the real records the Expo modules produce.
private val PRELUDE = """
  globalThis.__asset = (i) => ({
    id: 'asset-' + (1000000 + i),
    filename: 'IMG_2024_' + i + '.jpg',
    uri: 'content://media/external/images/media/' + (1000000 + i),
    mediaType: 'photo',
    width: 4032,
    height: 3024,
    creationTime: 1720000000000 + i,
    modificationTime: 1720000001000 + i,
    duration: 0,
    albumId: 'album-42',
  });
  globalThis.__contact = (i) => ({
    id: 'contact-' + i,
    contactType: 'person',
    name: 'First' + i + ' Last' + i,
    firstName: 'First' + i,
    lastName: 'Last' + i,
    company: 'Example Corp',
    jobTitle: 'Engineer',
    imageAvailable: false,
    phoneNumbers: [
      {id: 'p' + i + 'a', label: 'mobile', number: '+48 600 100 ' + (200 + i), digits: '48600100' + (200 + i), countryCode: 'pl', isPrimary: true},
      {id: 'p' + i + 'b', label: 'work', number: '+48 22 100 ' + (300 + i), digits: '4822100' + (300 + i), countryCode: 'pl', isPrimary: false},
    ],
    emails: [{id: 'e' + i, label: 'home', email: 'user' + i + '@example.com', isPrimary: true}],
    addresses: [{id: 'a' + i, label: 'home', street: 'Przykladowa ' + i, city: 'Warszawa', postalCode: '00-001', country: 'Poland'}],
  });
  globalThis.__event = (i) => ({
    id: 'event-' + i,
    calendarId: 'cal-1',
    title: 'Team sync #' + i,
    notes: 'Weekly sync covering roadmap, blockers and releases. Bring updates for your area.',
    location: 'Room 4.02, Warsaw office',
    startDate: 1720000000000 + i * 3600000,
    endDate: 1720003600000 + i * 3600000,
    allDay: false,
    availability: 'busy',
    status: 'confirmed',
    timeZone: 'Europe/Warsaw',
    organizerEmail: 'organizer@example.com',
    alarms: [{relativeOffset: -15}],
  });
  'ok';
""".trimIndent()

private val CASES = listOf(
  // The median call: a single options record (ImagePicker-style), ~150 B.
  FollyCase(
    "options record",
    "({mediaTypes: 'Images', allowsEditing: true, quality: 0.8, exif: false, base64: false, allowsMultipleSelection: false})",
    100_000,
  ),
  // Permission responses — the most frequent result record in real apps, ~100 B.
  FollyCase(
    "permission response",
    "({status: 'granted', expires: 'never', granted: true, canAskAgain: true})",
    100_000,
  ),
  // High-frequency event payload (sensors), ~60 B.
  FollyCase("sensor event {x,y,z}", "({x: 0.0231, y: -0.412, z: 9.81})", 200_000),
  // A nested single-result record (expo-location), ~200 B.
  FollyCase(
    "location response",
    "({coords: {latitude: 52.2297, longitude: 21.0122, altitude: 110.5, accuracy: 12.5, altitudeAccuracy: 8.0, heading: 271.2, speed: 1.3}, timestamp: 1752598000000, mocked: false})",
    50_000,
  ),
  // MediaLibrary.getAssetsAsync default page (first: 20) ~6 KB — the p95 shape.
  FollyCase(
    "mediaLibrary page[20]",
    "({assets: Array.from({length: 20}, (_, i) => __asset(i)), endCursor: 'cursor-20', hasNextPage: true, totalCount: 5231})",
    10_000,
  ),
  // A big MediaLibrary page (first: 100) ~30 KB — the everyday heavy tail.
  FollyCase(
    "mediaLibrary page[100]",
    "({assets: Array.from({length: 100}, (_, i) => __asset(i)), endCursor: 'cursor-100', hasNextPage: true, totalCount: 5231})",
    2_000,
  ),
  // The overflow pair: the same asset shape just under the 256 KiB buffer (still buffered)...
  FollyCase(
    "mediaLibrary page[700]",
    "({assets: Array.from({length: 700}, (_, i) => __asset(i)), endCursor: 'cursor-700', hasNextPage: true, totalCount: 5231})",
    300,
  ),
  // ...and just over it: the binary lanes pay the failed encode attempt, then fall back to the
  // per-field object path. Compare ns/element against page[700] to size the overflow penalty.
  FollyCase(
    "mediaLib page[1000] OVF",
    "({assets: Array.from({length: 1000}, (_, i) => __asset(i)), endCursor: 'cursor-1000', hasNextPage: true, totalCount: 5231})",
    200,
  ),
  // Contacts.getContactsAsync page (pageSize: 100), nested records, ~80 KB.
  FollyCase(
    "contacts page[100]",
    "({data: Array.from({length: 100}, (_, i) => __contact(i)), hasNextPage: false, hasPreviousPage: false})",
    500,
  ),
  // Calendar.getEventsAsync over a busy month, ~25 KB.
  FollyCase("calendar month[50]", "Array.from({length: 50}, (_, i) => __event(i))", 2_000),
  // SQLite.getAllAsync: rows as arrays of mixed columns, 200 x 8 ~ 15 KB.
  FollyCase(
    "sqlite rows[200x8]",
    "Array.from({length: 200}, (_, i) => [i, 'row-title-' + i, i * 1.5, i % 2 === 0, 'some text content for row ' + i, 1720000000 + i, null, 'tag-' + (i % 7)])",
    2_000,
  ),
  // Synthetic bulk-numeric reference point, for continuity with the older runs.
  FollyCase("doubles[1000]", "Array.from({length: 1000}, (_, i) => i * 0.5)", 2_000),
)

fun main() {
  ExpoHermes.ensureLoaded()

  HermesRuntime().use { runtime ->
    // The __follyBench hook lives on :test-support's ExpoTestSupport object.
    TestSupport.install(runtime)
    val available = runtime.evaluate("typeof ExpoTestSupport.__follyBench").getString() == "function"
    if (!available) {
      println("__follyBench is not compiled in.")
      println("Configure the native build with -DEXPO_FOLLY_BENCHMARK=ON (requires `brew install folly`):")
      println("  cmake -S test-support/src/main/cpp -B test-support/build/native -DEXPO_FOLLY_BENCHMARK=ON && ./gradlew :benchmark:runFollyBenchmark")
      return
    }

    runtime.evaluate(PRELUDE)

    val results = CASES.map { case ->
      val report = runtime.evaluate(
        "(() => { const p = ${case.payloadExpr}; return ExpoTestSupport.__follyBench(p, ${case.iters}); })()",
      ).getString()
      case.name to report.split(':').map { it.toDouble() }
    }

    println("=== dynamic value crossings on realistic Expo payloads (ns/op, lower is better) ===")
    println()
    println("-- C++ representation round trip (jsi::Value -> repr -> jsi::Value) --")
    println("%-22s %12s %14s %12s %11s %14s".format("case", "folly", "folly+JSON", "binary", "vs folly", "vs folly+JSON"))
    for ((name, r) in results) {
      val (folly, follyJson, binary) = r
      println(
        "%-22s %12.0f %14.0f %12.0f %10.2fx %13.2fx".format(
          name, folly, follyJson, binary, folly / binary, follyJson / binary,
        ),
      )
    }

    println()
    println("-- Full delivery to Kotlin objects (jsi::Value -> HashMap/ArrayList/boxed) --")
    println("(folly->Java: dynamic + per-field JNI, i.e. toHashMap(); cpp->Java: per-field JNI straight")
    println(" from JSI, no intermediate repr)")
    println("%-22s %12s %12s %11s".format("case", "folly->Java", "cpp->Java", "vs folly"))
    for ((name, r) in results) {
      val follyKt = r[3]
      val elementKt = r[4]
      println(
        "%-22s %12.0f %12.0f %10.2fx".format(name, follyKt, elementKt, follyKt / elementKt),
      )
    }
  }
}
