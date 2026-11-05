package io.github.expo.modules.v2.compiler.typegen

import io.github.expo.modules.v2.compiler.ir.SymbolFinder
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import java.io.File

/**
 * Typegen's entry point: writes what a compilation exports to JavaScript as a `scan-exports`
 * report, which `expo-modules generate-types --scanner <shim>` renders as `native.types.ts`.
 */
internal object Typegen {
  fun write(moduleFragment: IrModuleFragment, context: IrPluginContext, symbols: SymbolFinder, output: File) {
    val (surface, stats) = SurfaceCollector(context, symbols).collect(moduleFragment)
    val report = SurfaceJson.report(surface, stats)

    output.absoluteFile.parentFile.mkdirs()
    output.writeText(report)
  }
}
