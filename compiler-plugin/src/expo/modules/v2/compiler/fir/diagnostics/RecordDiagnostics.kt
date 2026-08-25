package expo.modules.v2.compiler.fir.diagnostics

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.rendering.RootDiagnosticRendererFactory
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.psi.KtParameter

/**
 * The frontend diagnostics [expo.modules.v2.compiler.fir.RecordCheckers] reports.
 */
object RecordDiagnostics {
  val RECORD_ON_UNSUPPORTED_DECLARATION by error1<KtClassLikeDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val RECORD_WITH_TYPE_PARAMETERS by error0<KtClassLikeDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val RECORD_WITHOUT_PRIMARY_CONSTRUCTOR by error0<KtClassLikeDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val RECORD_DECLARES_ITS_OWN_CODEC by error1<KtClassLikeDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val RECORD_PARAMETER_IS_NOT_A_PROPERTY by error0<KtParameter>()

  init {
    RootDiagnosticRendererFactory.registerFactory(RecordDiagnosticMessages)
  }
}

object RecordDiagnosticMessages : BaseDiagnosticRendererFactory() {
  override val MAP = KtDiagnosticFactoryToRendererMap("ExpoModulesV2").apply {
    put(
      RecordDiagnostics.RECORD_ON_UNSUPPORTED_DECLARATION,
      "@Record only applies to a non-abstract, non-inner, top-level or nested class - this is {0}",
      CommonRenderers.STRING,
    )
    put(
      RecordDiagnostics.RECORD_WITH_TYPE_PARAMETERS,
      "@Record cannot be used on a generic class - a RecordCodec is keyed on one Class<T>, and a " +
        "generic class has one java class for every instantiation",
    )
    put(
      RecordDiagnostics.RECORD_WITHOUT_PRIMARY_CONSTRUCTOR,
      "@Record requires a primary constructor - its parameters are the record's fields, and the " +
        "generated `decode` calls it positionally",
    )
    put(
      RecordDiagnostics.RECORD_DECLARES_ITS_OWN_CODEC,
      "@Record generates the codec, so `{0}` cannot be declared by hand - remove the hand-written " +
        "companion codec",
      CommonRenderers.STRING,
    )
    put(
      RecordDiagnostics.RECORD_PARAMETER_IS_NOT_A_PROPERTY,
      "A primary-constructor parameter of an @Record class must be a `val` or a `var` - `encode` " +
        "has no way to read a plain parameter back",
    )
  }
}
