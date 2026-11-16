package io.github.expo.modules.v2.compiler.typegen

import io.github.expo.modules.v2.compiler.ir.SymbolFinder
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import java.io.File

/**
 * Typegen's entry point: writes what a compilation exports to JavaScript as a `scan-exports`
 * report, which `expo-modules generate-types --scanner <scanner>` renders as `native.types.ts`.
 *
 * The report describes the whole compilation, so it needs every file of it in the IR: the Gradle
 * plugin keeps incremental compilation off, which it does for the record codecs anyway.
 */
internal object Typegen {
  fun write(
    moduleFragment: IrModuleFragment,
    context: IrPluginContext,
    symbols: SymbolFinder,
    output: File,
    messages: MessageCollector,
  ) {
    val report = try {
      val (surface, stats) = SurfaceCollector(context, symbols).collect(moduleFragment)
      SurfaceJson.report(surface, stats)
    } catch (e: Exception) {
      // Types are a convenience: a failure here must not fail the build of the module itself, so it
      // is a warning (which a build that treats warnings as errors still fails on). The old report
      // goes too, so a stale one is never taken for this compilation's.
      output.delete()
      messages.report(
        CompilerMessageSeverity.WARNING,
        "Expo Modules v2: could not describe this module for typegen, so no TypeScript types were " +
          "written to $output: $e",
      )
      return
    }

    output.absoluteFile.parentFile.mkdirs()
    output.writeText(report)
  }
}
