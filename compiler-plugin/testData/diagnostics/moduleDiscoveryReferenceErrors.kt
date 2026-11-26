// RUN_PIPELINE_TILL: FRONTEND

package io.github.expo.modules.v2.testdata

import io.github.expo.modules.v2.ExpoPackage
import io.github.expo.modules.v2.Module
import io.github.expo.modules.v2.discoveredExpoModules
import io.github.expo.modules.v2.discoveredExpoPackages

// The plugin replaces calls to these functions. A reference is not a call, so it would still point
// at the function, which throws.

val modules: List<Class<out Module>> by lazy { discoveredExpoModules() }

val packages: Map<String, ExpoPackage> by lazy { discoveredExpoPackages() }

val modulesReference: () -> List<Class<out Module>> = <!MODULE_DISCOVERY_REFERENCE!>::discoveredExpoModules<!>

val packagesByLazy: Map<String, ExpoPackage> by lazy(<!MODULE_DISCOVERY_REFERENCE!>::discoveredExpoPackages<!>)

fun unrelated(): Int = 1

val unrelatedReference: () -> Int = ::unrelated
