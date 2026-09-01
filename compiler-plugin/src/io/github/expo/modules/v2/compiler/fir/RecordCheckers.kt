package io.github.expo.modules.v2.compiler.fir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.fir.diagnostics.RecordDiagnostics
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.utils.isInner
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.Name

/**
 * Frontend validation for `@Record`.
 */
class RecordCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
  override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
    override val regularClassCheckers: Set<FirRegularClassChecker> = setOf(RecordClassChecker)
  }

  private object RecordClassChecker : FirRegularClassChecker(MppCheckerKind.Common) {
    override fun check(
      declaration: FirRegularClass,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val session = context.session
      if (!declaration.symbol.isRecord(session)) {
        return
      }

      if (!checkClassShape(declaration, context, reporter)) {
        return
      }

      // TODO(@lukmccall): Maybe we should allow it?
      checkNoHandWrittenCodec(declaration, context, reporter)

      val constructor = declaration.primaryConstructorIfAny(session)
      if (constructor == null) {
        reporter.reportOn(
          declaration.source,
          RecordDiagnostics.RECORD_WITHOUT_PRIMARY_CONSTRUCTOR,
          context,
        )
        return
      }

      val propertyNames = declaration.declarations
        .filterIsInstance<org.jetbrains.kotlin.fir.declarations.FirProperty>()
        .mapTo(mutableSetOf()) { it.name }

      for (parameter in constructor.valueParameterSymbols) {
        checkParameter(parameter, propertyNames, context, reporter)
      }
    }

    private fun checkClassShape(
      declaration: FirRegularClass,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ): Boolean {
      val rejected = when {
        declaration.classKind == ClassKind.INTERFACE -> "an interface"
        declaration.classKind == ClassKind.ENUM_CLASS -> "an enum class"
        declaration.classKind == ClassKind.ANNOTATION_CLASS -> "an annotation class"
        declaration.classKind == ClassKind.OBJECT -> "an object"
        declaration.status.modality == Modality.ABSTRACT -> "an abstract class"
        declaration.status.modality == Modality.SEALED -> "a sealed class"
        declaration.isInner -> "an inner class"
        declaration.isLocal -> "a local class"
        else -> null
      }

      if (rejected != null) {
        reporter.reportOn(
          declaration.source,
          RecordDiagnostics.RECORD_ON_UNSUPPORTED_DECLARATION,
          rejected,
          context,
        )
        return false
      }

      if (declaration.typeParameters.isNotEmpty()) {
        reporter.reportOn(
          declaration.source,
          RecordDiagnostics.RECORD_WITH_TYPE_PARAMETERS,
          context,
        )
        return false
      }

      return true
    }

    private fun checkNoHandWrittenCodec(
      declaration: FirRegularClass,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      val companion = declaration.companionObjectSymbol ?: return

      val implementsCodec = companion.resolvedSuperTypes.any {
        (it as? ConeClassLikeType)?.classId == Identifiers.Classes.RecordCodec
      }
      if (implementsCodec) {
        reporter.reportOn(
          declaration.source,
          RecordDiagnostics.RECORD_DECLARES_ITS_OWN_CODEC,
          "companion object : RecordCodec<${declaration.name}>",
          context,
        )
        return
      }

      val clashing = companion.declarationSymbols.firstOrNull {
        (it as? org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol<*>)?.name ==
          Identifiers.Names.CODEC_PROPERTY
      }
      if (clashing != null) {
        reporter.reportOn(
          declaration.source,
          RecordDiagnostics.RECORD_DECLARES_ITS_OWN_CODEC,
          Identifiers.Literals.CODEC_PROPERTY,
          context,
        )
      }
    }

    private fun checkParameter(
      parameter: FirValueParameterSymbol,
      propertyNames: Set<Name>,
      context: CheckerContext,
      reporter: DiagnosticReporter,
    ) {
      if (parameter.name !in propertyNames) {
        reporter.reportOn(
          parameter.source,
          RecordDiagnostics.RECORD_PARAMETER_IS_NOT_A_PROPERTY,
          context,
        )
        return
      }
    }
  }
}
