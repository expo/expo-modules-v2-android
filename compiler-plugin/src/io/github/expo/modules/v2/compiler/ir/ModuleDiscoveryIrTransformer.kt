package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.ModuleDiscoveryOrigin
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.types.Variance

/**
 * Replaces every `discoveredExpoModules()` call with the list it stands for:
 *
 * ```
 * listOf<Class<out Module>>(First::class.java, Second::class.java, Local::class.java)
 * ```
 *
 * The consumer half of module discovery. The classes come from two places: the hint interfaces
 * [scanner] finds on compile classpath, each followed back to the module its `module()` member
 * returns, and the `@ExpoModule` classes of this very compilation, which have no hint on the
 * classpath yet. Sorted by name so the list is stable across builds.
 */
internal class ModuleDiscoveryIrTransformer(
  private val context: IrPluginContext,
  private val symbols: SymbolFinder,
  private val poet: TypeDescriptorPoet,
  private val scanner: ModuleHintScanner,
  private val moduleFragment: IrModuleFragment,
) : IrElementTransformerVoid() {
  private val irBuiltIns = context.irBuiltIns

  private val modules: List<IrClassSymbol> by lazy {
    (classpathModules() + localModules())
      .distinct()
      .sortedBy { it.owner.kotlinFqName.asString() }
  }

  /** `listOf(vararg elements: T)` */
  private val listOf: IrSimpleFunctionSymbol by lazy {
    context
      .referenceFunctions(Identifiers.Callables.listOf)
      .single { candidate ->
        val regular = candidate.owner.parameters.filter { it.kind == IrParameterKind.Regular }
        regular.size == 1 && regular[0].varargElementType != null
      }
  }

  override fun visitCall(expression: IrCall): IrExpression {
    val call = super.visitCall(expression)
    if (!expression.symbol.owner.isDiscoveredExpoModules()) {
      return call
    }
    return moduleList()
  }

  private fun IrSimpleFunction.isDiscoveredExpoModules(): Boolean =
    name == Identifiers.Callables.discoveredExpoModules.callableName &&
      parentClassOrNull == null &&
      getPackageFragment().packageFqName == Identifiers.Callables.discoveredExpoModules.packageName

  private fun moduleList(): IrExpression {
    // Class<out Module>
    val elementType = symbols.classes.javaLangClass.typeWithArguments(
      listOf(makeTypeProjection(symbols.classes.module.owner.defaultType, Variance.OUT_VARIANCE)),
    )
    val elements = modules.map { poet.javaClass(it.owner.defaultType) }

    return IrSyntheticCallImpl(
      type = irBuiltIns.listClass.typeWith(elementType),
      symbol = listOf,
      typeArgumentsCount = 1,
      origin = ModuleDiscoveryOrigin,
    ).apply {
      typeArguments[0] = elementType
      arguments[0] = IrSyntheticVarargImpl(
        type = irBuiltIns.arrayClass.typeWith(elementType),
        varargElementType = elementType,
        elements = elements,
      )
    }
  }

  private fun classpathModules(): List<IrClassSymbol> = scanner.hints.map { hint ->
    val hintClass = context.referenceClass(hint)
      ?: error(
        "Expo Modules v2: the compile classpath holds a module hint, ${hint.asSingleFqName()}, " +
          "that the compiler cannot resolve. The library it came from is probably only partly on " +
          "the classpath; make sure the dependency that compiled the module is declared as a " +
          "compile dependency (api/implementation), not runtimeOnly.",
      )
    val member = hintClass.owner.functions.firstOrNull { it.name == Identifiers.Hints.MEMBER }
    member?.returnType?.classOrNull
      ?: error(
        "Expo Modules v2: ${hint.asSingleFqName()} is not a module hint this compiler plugin " +
          "understands - it has no `${Identifiers.Hints.MEMBER}()` member naming a module. It was " +
          "compiled by a different version of the plugin; align the plugin version across the " +
          "modules of this build.",
      )
  }

  private fun localModules(): List<IrClassSymbol> {
    val found = mutableListOf<IrClassSymbol>()
    val localVisitor = object : IrVisitorVoid() {
      override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
      }

      override fun visitClass(declaration: IrClass) {
        if (declaration.hasAnnotation(Identifiers.FqNames.EXPO_MODULE_ANNOTATION)) {
          found += declaration.symbol
        }
        declaration.acceptChildrenVoid(this)
      }
    }
    moduleFragment.acceptChildrenVoid(localVisitor)
    return found
  }
}
