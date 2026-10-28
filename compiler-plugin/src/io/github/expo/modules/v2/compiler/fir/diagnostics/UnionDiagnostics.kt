package io.github.expo.modules.v2.compiler.fir.diagnostics

import io.github.expo.modules.v2.compiler.fir.ExpoDiagnosticsContainer
import io.github.expo.modules.v2.compiler.fir.expoRendererMap
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration

/**
 * The frontend diagnostics [io.github.expo.modules.v2.compiler.fir.UnionCheckers] reports.
 */
object UnionDiagnostics : ExpoDiagnosticsContainer() {
  val UNION_ON_UNSUPPORTED_DECLARATION by error1<KtClassLikeDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val UNION_WITH_TYPE_PARAMETERS by error0<KtClassLikeDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val UNION_DECLARES_ITS_OWN_CODEC by error1<KtClassLikeDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val UNION_WITHOUT_VARIANTS by error0<KtClassLikeDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val UNION_UNREACHABLE_VARIANT by error1<KtClassLikeDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val UNION_UNSUPPORTED_VARIANT by error1<KtClassLikeDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val UNION_VARIANT_WITH_NULLABLE_VALUE by error0<KtClassLikeDeclaration>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val UNION_VARIANT_IS_NOT_A_RECORD by error1<KtClassLikeDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )
  val UNION_VARIANT_SHADOWS_DISCRIMINATOR by error1<KtClassLikeDeclaration, String>(
    SourceElementPositioningStrategies.DECLARATION_NAME,
  )

  override fun getRendererFactory(): BaseDiagnosticRendererFactory = UnionDiagnosticMessages

  init {
    registerRenderer()
  }
}

object UnionDiagnosticMessages : BaseDiagnosticRendererFactory() {
  override val MAP by expoRendererMap("ExpoModulesV2") {
    put(
      UnionDiagnostics.UNION_ON_UNSUPPORTED_DECLARATION,
      "@Union only applies to a sealed interface or a sealed class - this is {0}",
      CommonRenderers.STRING,
    )
    put(
      UnionDiagnostics.UNION_WITH_TYPE_PARAMETERS,
      "@Union cannot be used on a generic type - a UnionCodec is keyed on one Class<T>, and a " +
        "generic type has one java class for every instantiation",
    )
    put(
      UnionDiagnostics.UNION_DECLARES_ITS_OWN_CODEC,
      "@Union generates the codec, so `{0}` cannot be declared by hand",
      CommonRenderers.STRING,
    )
    put(
      UnionDiagnostics.UNION_WITHOUT_VARIANTS,
      "An @Union type needs at least one variant - a class or object that directly implements it",
    )
    put(
      UnionDiagnostics.UNION_UNREACHABLE_VARIANT,
      "JavaScript never picks one variant of this union - within one kind of value, the first " +
        "variant declared that takes it wins: {0}",
      CommonRenderers.STRING,
    )
    put(
      UnionDiagnostics.UNION_UNSUPPORTED_VARIANT,
      "A variant of an @Union type is an object, an @Record class, or a class with exactly one " +
        "constructor property - this is {0}",
      CommonRenderers.STRING,
    )
    put(
      UnionDiagnostics.UNION_VARIANT_WITH_NULLABLE_VALUE,
      "The value of an @Union variant cannot be nullable - JavaScript's null has no variant to " +
        "pick. Make the union itself nullable where it is used instead",
    )
    put(
      UnionDiagnostics.UNION_VARIANT_IS_NOT_A_RECORD,
      "A union with a discriminator tells its objects apart by that property, so its object " +
        "variants must be @Record classes - {0} is not",
      CommonRenderers.STRING,
    )
    put(
      UnionDiagnostics.UNION_VARIANT_SHADOWS_DISCRIMINATOR,
      "This variant has a property named ''{0}'', which is the union''s discriminator - rename " +
        "one of them",
      CommonRenderers.STRING,
    )
  }
}
