package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.ClasspathPackage
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
import org.jetbrains.kotlin.ir.types.IrType
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
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.types.Variance

/**
 * Replaces every `discoveredExpoModules()` call with the list it stands for:
 *
 * ```
 * listOf<Class<out Module>>(First::class.java, Second::class.java, Local::class.java)
 * ```
 *
 * and every `discoveredExpoPackages()` call with the same modules grouped by package:
 *
 * ```
 * mapOf<String, ExpoPackage>(Pair("expo-font", ExpoPackage(listOf(First::class.java, ...))), ...)
 * ```
 *
 * The consumer half of module discovery. The classes come from two places: the hint interfaces
 * [scanner] finds on compile classpath, each followed back to the module its `module()` member
 * returns, and the `@ExpoModule` classes of this very compilation, which have no hint on the
 * classpath yet. A hint's package is the one [classpathPackages] gives the classpath entry the hint
 * was found in, so the compilation that declares a module needs to know nothing about packages.
 * The modules of this compilation, and those in an entry without a package, are in no package.
 * Modules and packages are sorted by name so the result is stable across builds.
 */
internal class ModuleDiscoveryIrTransformer(
  private val context: IrPluginContext,
  private val symbols: SymbolFinder,
  private val poet: TypeDescriptorPoet,
  private val scanner: ModuleHintScanner,
  private val moduleFragment: IrModuleFragment,
  classpathPackages: List<ClasspathPackage>,
) : IrElementTransformerVoid() {
  private val irBuiltIns = context.irBuiltIns

  /** A discovered module and the package it belongs to, if it has one. */
  private class DiscoveredModule(val module: IrClassSymbol, val packageName: String?)

  private val discovered: List<DiscoveredModule> by lazy {
    classpathModules() + localModules().map { DiscoveredModule(it, packageName = null) }
  }

  private val packageIndex = ClasspathPackageIndex(classpathPackages)

  private val modules: List<IrClassSymbol> by lazy {
    discovered.map { it.module }.sortedDistinct()
  }

  private val packages: List<Pair<String, List<IrClassSymbol>>> by lazy {
    packageIndex.checkAnyEntryIsOn(scanner.classpath)
    discovered
      .mapNotNull { found -> found.packageName?.let { it to found.module } }
      .groupBy({ (name, _) -> name }, { (_, module) -> module })
      .map { (name, modules) -> name to modules.sortedDistinct() }
      .sortedBy { (name, _) -> name }
  }

  /** `listOf(vararg elements: T)` */
  private val listOf: IrSimpleFunctionSymbol by lazy { varargFunction(Identifiers.Callables.listOf) }

  /** `mapOf(vararg pairs: Pair<K, V>)` */
  private val mapOf: IrSimpleFunctionSymbol by lazy { varargFunction(Identifiers.Callables.mapOf) }

  /** `Class<out Module>` */
  private val moduleClassType: IrType by lazy {
    symbols.classes.javaLangClass.typeWithArguments(
      listOf(makeTypeProjection(symbols.classes.module.owner.defaultType, Variance.OUT_VARIANCE)),
    )
  }

  /** `List<Class<out Module>>` */
  private val moduleListType: IrType by lazy { irBuiltIns.listClass.typeWith(moduleClassType) }

  override fun visitCall(expression: IrCall): IrExpression {
    val call = super.visitCall(expression)
    val function = expression.symbol.owner
    return when {
      function.isIntrinsic(Identifiers.Callables.discoveredExpoModules) -> moduleList(modules)
      function.isIntrinsic(Identifiers.Callables.discoveredExpoPackages) -> packageMap()
      else -> call
    }
  }

  private fun IrSimpleFunction.isIntrinsic(callable: CallableId): Boolean =
    name == callable.callableName &&
      parentClassOrNull == null &&
      getPackageFragment().packageFqName == callable.packageName

  private fun moduleList(modules: List<IrClassSymbol>): IrExpression {
    val elements = modules.map { poet.javaClass(it.owner.defaultType) }

    return IrSyntheticCallImpl(
      type = moduleListType,
      symbol = listOf,
      typeArgumentsCount = 1,
      origin = ModuleDiscoveryOrigin,
    ).apply {
      typeArguments[0] = moduleClassType
      arguments[0] = IrSyntheticVarargImpl(
        type = irBuiltIns.arrayClass.typeWith(moduleClassType),
        varargElementType = moduleClassType,
        elements = elements,
      )
    }
  }

  private fun packageMap(): IrExpression {
    val keyType = irBuiltIns.stringType
    val valueType = symbols.classes.expoPackage.owner.defaultType
    // Pair<String, ExpoPackage>
    val pairType = symbols.classes.pair.typeWith(keyType, valueType)
    val pairs = packages.map { (name, modules) ->
      IrSyntheticConstructorCallImpl(
        type = pairType,
        symbol = symbols.constructors.pair,
        typeArgumentsCount = 2,
      ).apply {
        typeArguments[0] = keyType
        typeArguments[1] = valueType
        arguments[0] = poet.string(name)
        arguments[1] = IrSyntheticConstructorCallImpl(
          type = valueType,
          symbol = symbols.constructors.expoPackage,
        ).apply {
          arguments[0] = moduleList(modules)
        }
      }
    }

    return IrSyntheticCallImpl(
      type = irBuiltIns.mapClass.typeWith(keyType, valueType),
      symbol = mapOf,
      typeArgumentsCount = 2,
      origin = ModuleDiscoveryOrigin,
    ).apply {
      typeArguments[0] = keyType
      typeArguments[1] = valueType
      arguments[0] = IrSyntheticVarargImpl(
        type = irBuiltIns.arrayClass.typeWith(pairType),
        varargElementType = pairType,
        elements = pairs,
      )
    }
  }

  private fun varargFunction(callable: CallableId): IrSimpleFunctionSymbol =
    context
      .referenceFunctions(callable)
      .single { candidate ->
        val regular = candidate.owner.parameters.filter { it.kind == IrParameterKind.Regular }
        regular.size == 1 && regular[0].varargElementType != null
      }

  private fun List<IrClassSymbol>.sortedDistinct(): List<IrClassSymbol> =
    distinct().sortedBy { it.owner.kotlinFqName.asString() }

  private fun classpathModules(): List<DiscoveredModule> = scanner.hints.map { hint ->
    val hintClass = context.referenceClass(hint)
      ?: error(
        "Expo Modules v2: the compile classpath holds a module hint, ${hint.asSingleFqName()}, " +
          "that the compiler cannot resolve. The library it came from is probably only partly on " +
          "the classpath; make sure the dependency that compiled the module is declared as a " +
          "compile dependency (api/implementation), not runtimeOnly.",
      )
    val member = hintClass.owner.functions.firstOrNull { it.name == Identifiers.Hints.MEMBER }
    val module = member?.returnType?.classOrNull
      ?: error(
        "Expo Modules v2: ${hint.asSingleFqName()} is not a module hint this compiler plugin " +
          "understands - it has no `${Identifiers.Hints.MEMBER}()` member naming a module. It was " +
          "compiled by a different version of the plugin; align the plugin version across the " +
          "modules of this build.",
      )
    DiscoveredModule(module, scanner.rootOf(hint)?.let(packageIndex::packageOf))
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
