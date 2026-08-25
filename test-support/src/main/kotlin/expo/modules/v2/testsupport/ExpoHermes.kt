package expo.modules.v2.testsupport

import io.github.expo.hermes.HermesEnv
import expo.modules.v2.core.ExpoModulesV2

/**
 * Loads the two native libraries a desktop runtime needs: the Hermes engine
 * (`libhermes-test-env`, from hermes-tests-environment) and this project's JNI bridge
 * (`libexpo-kolibri`).
 *
 * The engine goes first: the bridge links against it for the one process-wide copy of JSI, and the
 * dynamic linker binds that dependency to the already-loaded image.
 *
 * Both are resolved through `java.library.path`, so the process must be started with
 * `-Djava.library.path=<engine native-libs dir>:<api native-libs dir>` (Gradle unpacks the engine's
 * under `api/build/hermes-env-libs` and builds :api's under `api/build/native-libs`).
 */
object ExpoHermes {
  fun ensureLoaded() {
    HermesEnv.load()
    ExpoModulesV2.load()
  }
}
