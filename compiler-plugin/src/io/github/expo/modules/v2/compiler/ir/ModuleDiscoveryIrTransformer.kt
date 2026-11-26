package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.ModuleDiscoveryOrigin
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
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
import java.io.File

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
 * classpath yet. A classpath module's package is the one its hint records in `@ModuleHintPackage`,
 * put there by the compilation that declared the module when it was given a package name. A module
 * of this compilation is in [ownPackage], the package this compilation was given, if any. Modules
 * and packages are sorted by name so the result is stable across builds.
 *
 * When some classpath modules have a package but others come from hints without one, those were
 * most likely compiled without a package name - by an older plugin, or a library whose build does
 * not know its package - so expanding `discoveredExpoPackages()` warns about them through [messages].
 */
internal class ModuleDiscoveryIrTransformer(
  private val context: IrPluginContext,
  private val symbols: SymbolFinder,
  private val poet: TypeDescriptorPoet,
  private val scanner: ModuleHintScanner,
  private val moduleFragment: IrModuleFragment,
  private val ownPackage: String?,
  private val messages: MessageCollector,
) : IrElementTransformerVoid() {
  private val irBuiltIns = context.irBuiltIns

  /**
   * A discovered module and the package it belongs to, if it has one. [root] is the classpath entry
   * a classpath module came from; a module of this compilation has none.
   */
  private class DiscoveredModule(val module: IrClassSymbol, val packageName: String?, val root: File? = null)

  private val discovered: List<DiscoveredModule> by lazy {
    classpathModules() + localModules().map { DiscoveredModule(it, packageName = ownPackage) }
  }

  private val modules: List<IrClassSymbol> by lazy {
    discovered.map { it.module }.sortedDistinct()
  }

  private val packages: List<Pair<String, List<IrClassSymbol>>> by lazy {
    reportPackageProblems()
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

  private fun reportPackageProblems() {
    val fromClasspath = discovered.filter { it.root != null }
    val unmapped = fromClasspath.filter { it.packageName == null }
    // Every classpath module has a package, or none does - then nothing on this classpath was built
    // with a package name, and an empty map is the answer.
    if (unmapped.isEmpty() || unmapped.size == fromClasspath.size) {
      return
    }
    val listed = unmapped
      .sortedBy { it.module.owner.kotlinFqName.asString() }
      .joinToString(separator = "\n") { "  ${it.module.owner.kotlinFqName} (from ${it.root})" }
    messages.report(
      CompilerMessageSeverity.WARNING,
      "Expo Modules v2: discoveredExpoPackages() leaves ${unmapped.size} classpath module(s) out of " +
        "every package, because their hints record none: the library that declares each was compiled " +
        "without a package name. Set `expoModulesV2.packageName` in that library's build, or give it " +
        "a package.json one directory up:\n$listed",
    )
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
    DiscoveredModule(module, scanner.packageOf(hint), scanner.rootOf(hint))
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
