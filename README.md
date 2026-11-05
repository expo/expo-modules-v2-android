# Expo Modules API v2 Android

## TypeScript types (typegen)

The compiler plugin can describe what a module exports to JavaScript, so its TypeScript types come
from the Kotlin it is compiled from. It writes the same report `ExpoModulesMacros scan-exports`
writes for the iOS v2 Swift sources, so `expo-modules generate-types` from `expo-modules-cli`
renders it into the same `native.types.ts`.

Turn it on in the module's `build.gradle.kts`:

```kotlin
plugins {
  id("io.github.expo.modules.v2")
}

expoModulesV2 {
  typegen {
    enabled = true
    // compilation = "freeDebug" // by default `debug` (or a flavor's), then `main`, then `release`
  }
}
```

Each non-test Kotlin compilation then writes
`build/generated/expo-modules-v2/typegen/<compile task>/exports.json`. The `expoModulesTypegen`
task builds one of them and writes `build/generated/expo-modules-v2/typegen/scanner`, which the CLI
runs in place of the Swift scanner and which prints that report. Run the task again after changing
the Kotlin, then the CLI:

```sh
./gradlew :my-module:expoModulesTypegen
npx expo-modules generate-types --scanner <module build dir>/generated/expo-modules-v2/typegen/scanner
```

The scanner is a POSIX shell script, so this does not work on Windows yet. Both platforms write the
same `native.types.ts`, so for now a run with this scanner replaces what the Swift one wrote; merging
the two reports needs support in `expo-modules-cli`.

The report is built from the resolved IR by the bridge's own rules, so it reports how values cross
at run time:

| Kotlin | TypeScript |
|---|---|
| `Int`, `Long`, `Float`, `Double` | `number` (a `Long` crosses as a double) |
| `Boolean`, `String` | `boolean`, `string` |
| `T?` | `T \| null`, and optional when it is a trailing parameter or a record field |
| `List<T>`, `Set<T>`, `Array<T>`, `IntArray` and other primitive arrays | `T[]` |
| `ByteArray` | `ArrayBuffer` |
| `Map<K, V>` | `Record<K, V>` |
| `Duration` | `number`, in seconds |
| `File`, `URL`, `URI`, `Path` | `string` |
| `TypedArray` | `TypedArray` from `expo` |
| `JavaScriptObject` | `object` |
| `Any`, `JavaScriptValue` | `unknown` |
| `suspend fun`, `Promise<T>` | `Promise<T>` |
| `@Record` class | a type with one property per constructor field; a defaulted or nullable one is optional |
| enum | a TS `enum` of the entry names, or of the values of its one `String` or `Int` property |
| `@ExpoSharedObject` class | a class extending `SharedObject` |

A record declared in another Gradle module is described as well: a TypeScript object type is
structural, so a copy is the same type. A dependency's enum and shared objects are not - a TS enum
is nominal - so they are rendered as `unknown` until a module can import its dependencies' types.
