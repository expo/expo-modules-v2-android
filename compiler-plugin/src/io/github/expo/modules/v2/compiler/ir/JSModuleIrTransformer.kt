package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.BufferChoice
import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.JSModuleKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isSubclassOf
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addToStdlib.ifTrue

/**
 * Generates a `@JS` module's registration and its trampolines
 */
class JSModuleIrTransformer(
  private val context: IrPluginContext,
  private val symbols: SymbolFinder,
  private val poet: TypeDescriptorPoet,
) : IrVisitorVoid() {
  private val irBuiltIns = context.irBuiltIns
  private val policy = TransportPolicy(context, symbols)
  private val trampolines = TrampolinePoet(context, symbols, poet)

  private sealed interface Exported {
    val jsName: String
    val needsTrampoline: Boolean
  }

  private class ExportedFunction(
    override val jsName: String,
    val function: IrSimpleFunction,
    val arguments: List<ValuePlan>,
    val result: ValuePlan,
  ) : Exported {
    var trampolineName: String? = null

    val isAsync: Boolean
      get() = function.isSuspend

    override val needsTrampoline: Boolean
      get() = isAsync || (arguments + result).any { it.buffered || !it.passthrough }
  }

  private class ExportedSharedClass(
    val jsName: String,
    val sharedClass: IrClass,
    val arguments: List<ValuePlan>,
  ) {
    val trampolineName: String
      get() = Identifiers.Literals.CONSTRUCTOR_TRAMPOLINE
  }

  private class ExportedProperty(
    override val jsName: String,
    val property: IrProperty,
    val getterPlan: ValuePlan,
    val setterPlan: ValuePlan?,
  ) : Exported {
    var trampolineBase: String? = null

    override val needsTrampoline: Boolean
      get() = listOfNotNull(getterPlan, setterPlan).any { it.buffered || !it.passthrough }
  }

  override fun visitElement(element: IrElement) {
    element.acceptChildrenVoid(this)
  }

  override fun visitClass(declaration: IrClass) {
    declaration.acceptChildrenVoid(this)

    val annotation = declaration.getAnnotation(Identifiers.FqNames.JS_ANNOTATION) ?: return
    generate(declaration, annotation)
  }

  private fun generate(moduleClass: IrClass, annotation: IrConstructorCall) {
    val classChoice = annotation.bufferChoice(Identifiers.Names.ARG_BUFFER)
    val exports = exportedOf(moduleClass, classChoice)
    val jsName = annotation.stringArgument(Identifiers.Names.ARG_NAME)
      ?.takeIf { it.isNotEmpty() }
      ?: moduleClass.name.asString()

    generateTrampolines(moduleClass, exports)
    if (moduleClass.isSubclassOf(symbols.classes.sharedObject.owner)) {
      generateConstructorTrampoline(moduleClass)
    }
    generateDefine(moduleClass, jsName, exports, sharedClassesOf(moduleClass, annotation))
    if (moduleClass.isSubclassOf(symbols.classes.sharedObject.owner)) {
      generateRegistration(moduleClass, jsName)
    }
  }

  private fun sharedClassesOf(
    moduleClass: IrClass,
    annotation: IrConstructorCall,
  ): List<ExportedSharedClass> {
    if (!moduleClass.isSubclassOf(symbols.classes.module.owner)) {
      // A shared-object class exports no classes of its own.
      return emptyList()
    }

    val nested = moduleClass.declarations
      .filterIsInstance<IrClass>()
      .filter { it.isSubclassOf(symbols.classes.sharedObject.owner) }

    val listed = annotation.classReferenceArgument(Identifiers.Names.ARG_CLASSES)

    return (nested + listed)
      .distinct()
      .filter { it.hasAnnotation(Identifiers.FqNames.JS_ANNOTATION) }
      .mapNotNull(::exportedSharedClassOf)
  }

  private fun exportedConstructorOf(sharedClass: IrClass): IrConstructor? =
    sharedClass.constructors.firstOrNull { it.hasAnnotation(Identifiers.FqNames.JS_ANNOTATION) }

  private fun constructorArgumentsOf(
    sharedClass: IrClass,
    constructor: IrConstructor,
  ): List<ValuePlan> {
    val classChoice = sharedClass
      .getAnnotation(Identifiers.FqNames.JS_ANNOTATION)
      .bufferChoice(Identifiers.Names.ARG_BUFFER)

    return constructor.parameters
      .filter { it.kind == IrParameterKind.Regular }
      .map { parameter ->
        val own = parameter
          .getAnnotation(Identifiers.FqNames.JS_ANNOTATION)
          .bufferChoice(Identifiers.Names.ARG_BUFFER)
        policy.plan(parameter.type, own.orElse(classChoice), Crossing.INBOUND)
      }
  }

  private fun exportedSharedClassOf(sharedClass: IrClass): ExportedSharedClass? {
    val constructor = exportedConstructorOf(sharedClass) ?: return null
    val annotation = sharedClass.getAnnotation(Identifiers.FqNames.JS_ANNOTATION)

    return ExportedSharedClass(
      jsName = annotation
        .stringArgument(Identifiers.Names.ARG_NAME)
        ?.takeIf { it.isNotEmpty() }
        ?: sharedClass.name.asString(),
      sharedClass = sharedClass,
      arguments = constructorArgumentsOf(sharedClass, constructor),
    )
  }

  private fun generateConstructorTrampoline(sharedClass: IrClass) {
    val constructor = exportedConstructorOf(sharedClass) ?: return

    trampolines.constructorTrampoline(
      sharedClass = sharedClass,
      name = Identifiers.Literals.CONSTRUCTOR_TRAMPOLINE,
      constructor = constructor,
      arguments = constructorArgumentsOf(sharedClass, constructor),
    )
  }

  private fun generateTrampolines(moduleClass: IrClass, exported: List<Exported>) {
    exported
      .filter { e -> e.needsTrampoline }
      .forEach { e ->
        when (e) {
          is ExportedFunction -> {
            val name = e.function.name.asString() + Identifiers.Literals.TRAMPOLINE_SUFFIX
            trampolines.functionTrampoline(
              moduleClass = moduleClass,
              name = name,
              target = e.function,
              arguments = e.arguments,
              result = e.result
            )
            e.trampolineName = name
          }

          is ExportedProperty -> {
            val base = e.property.name.asString().removeIsPrefix()
            val suffix = base.replaceFirstChar { it.titlecase() } + Identifiers.Literals.TRAMPOLINE_SUFFIX
            trampolines.propertyGetter(
              moduleClass = moduleClass,
              name = "get$suffix",
              property = e.property,
              plan = e.getterPlan,
            )
            e.setterPlan?.let { setterPlan ->
              trampolines.propertySetter(
                moduleClass = moduleClass,
                name = "set$suffix",
                property = e.property,
                plan = setterPlan,
              )
            }
            e.trampolineBase = base + Identifiers.Literals.TRAMPOLINE_SUFFIX
          }
        }
      }
  }

  /** Mirrors `ModuleBuilder`'s rule: `isReady` accessors are `isReady`/`setReady`, not `getIsReady`. */
  private fun String.removeIsPrefix(): String =
    if (length > 2 && startsWith("is") && !this[2].isLowerCase()) {
      substring(2).replaceFirstChar { it.lowercase() }
    } else {
      this
    }

  private fun exportedOf(moduleClass: IrClass, classChoice: BufferChoice): List<Exported> =
    moduleClass
      .declarations
      .mapNotNull { declaration ->
        when (declaration) {
          is IrSimpleFunction ->
            // An accessor carries its property's annotation; the property is the export, not the pair.
            if (declaration.correspondingPropertySymbol != null) {
              null
            } else {
              declaration.getAnnotation(Identifiers.FqNames.JS_ANNOTATION)?.let { annotation ->
                makeExportedFunction(declaration, annotation, classChoice)
              }
            }

          is IrProperty ->
            declaration.getAnnotation(Identifiers.FqNames.JS_ANNOTATION)?.let { annotation ->
              makeExportedProperty(declaration, annotation, classChoice)
            }

          else -> null
        }
      }

  private fun makeExportedFunction(
    function: IrSimpleFunction,
    annotation: IrConstructorCall,
    classChoice: BufferChoice,
  ): ExportedFunction {
    val memberChoice = annotation
      .bufferChoice(Identifiers.Names.ARG_BUFFER)
      .orElse(classChoice)

    val returnChoice = annotation
      .bufferChoice(Identifiers.Names.ARG_RETURN_BUFFER)
      .orElse(memberChoice)

    val arguments = function.parameters
      .filter { it.kind == IrParameterKind.Regular }
      .map { parameter ->
        val own = parameter
          .getAnnotation(Identifiers.FqNames.JS_ANNOTATION)
          .bufferChoice(Identifiers.Names.ARG_BUFFER)
        policy.plan(parameter.type, own.orElse(memberChoice), Crossing.INBOUND)
      }

    return ExportedFunction(
      jsName = annotation
        .stringArgument(Identifiers.Names.ARG_NAME)
        ?.takeIf { it.isNotEmpty() }
        ?: function.name.asString(),
      function = function,
      arguments = arguments,
      result = policy.plan(function.returnType, returnChoice, Crossing.RESULT),
    )
  }

  private fun makeExportedProperty(
    property: IrProperty,
    annotation: IrConstructorCall,
    classChoice: BufferChoice,
  ): ExportedProperty {
    val getter = property.getter
      ?: error("@JS: ${"${property.name} has no getter"}")

    val choice = annotation
      .bufferChoice(Identifiers.Names.ARG_BUFFER)
      .orElse(classChoice)

    val returnChoice = annotation
      .bufferChoice(Identifiers.Names.ARG_RETURN_BUFFER)
      .orElse(choice)

    val type = getter.returnType

    return ExportedProperty(
      jsName = annotation
        .stringArgument(Identifiers.Names.ARG_NAME)
        ?.takeIf { it.isNotEmpty() }
        ?: property.name.asString(),
      property = property,
      getterPlan = policy.plan(type, returnChoice, Crossing.RESULT),
      setterPlan = property.isVar.ifTrue { policy.plan(type, choice, Crossing.INBOUND) },
    )
  }

  /**
   * Creates `define$ExpoModulesV2`.
   *
   * A module overrides the one it inherits, because a module is always registered as an instance. A
   * shared-object class gets a fresh receiverless function instead, which the JVM emits as a static:
   * its description has to be readable before any instance of it exists, since a declared parameter
   * type names the class long before one does.
   */
  private fun makeDefineFunction(moduleClass: IrClass): IrSimpleFunction {
    if (moduleClass.isSubclassOf(symbols.classes.sharedObject.owner)) {
      return makeStaticDefineFunction(moduleClass)
    }

    val define = moduleClass
      .declarations
      .filterIsInstance<IrSimpleFunction>()
      .firstOrNull { it.name == Identifiers.Names.DEFINE_FUNCTION }
      ?: error(
        "@JS: ${
          "${moduleClass.kotlinFqName} has no inherited ${Identifiers.Literals.DEFINE_FUNCTION}; " +
            "the frontend should have rejected a @JS class that is neither a Module nor a SharedObject"
        }"
      )

    define.isFakeOverride = false
    define.origin = IrDeclarationOrigin.GeneratedByPlugin(JSModuleKey)
    define.modality = Modality.FINAL
    // Whichever base declared the hook: a shared-object class overrides SharedObject's, not
    // Module's, even though both take the same builder.
    define.overriddenSymbols = listOf(symbols.functions.define)
    define.parameters
      .single { it.kind == IrParameterKind.DispatchReceiver }
      .type = moduleClass.defaultType
    return define
  }

  private fun generateRegistration(sharedClass: IrClass, jsName: String) {
    val define = sharedClass
      .declarations
      .filterIsInstance<IrSimpleFunction>()
      .firstOrNull { it.name == Identifiers.Names.DEFINE_FUNCTION }
      ?: return

    val register = sharedClass.addSyntheticFunction {
      name = Name.identifier(Identifiers.Literals.REGISTER_FUNCTION)
      returnType = irBuiltIns.intType
      visibility = DescriptorVisibilities.PRIVATE
      modality = Modality.FINAL
      origin = IrDeclarationOrigin.GeneratedByPlugin(JSModuleKey)
    }

    val builder = buildSyntheticVariable(
      parent = register,
      origin = IrDeclarationOrigin.DEFINED,
      name = Name.identifier(Identifiers.Literals.BUILDER_PARAMETER),
      type = symbols.classes.moduleBuilder.owner.defaultType,
    ).apply {
      initializer = newInstance(
        symbols.classes.moduleBuilder.owner.primaryConstructor!!.symbol,
        arguments = emptyList(),
        type = symbols.classes.moduleBuilder.owner.defaultType,
      )
    }

    register.body = context.irFactory.createSyntheticBlockBody().apply {
      statements += builder
      // The describer's own return value is the JavaScript name, which this function already has.
      statements += IrSyntheticCallImpl(irBuiltIns.stringType, define.symbol).apply {
        arguments[0] = builder.get()
      }
      statements += IrSyntheticReturnImpl(
        type = irBuiltIns.nothingType,
        returnTargetSymbol = register.symbol,
        value = callStatic(
          symbols.functions.registerSharedClass,
          symbols.classes.sharedObjectRegistry,
          arguments = listOf(
            poet.string(jsName),
            poet.javaClass(sharedClass.defaultType),
            builder.get(),
          ),
          returnType = irBuiltIns.intType,
        ),
      )
    }
    register.patchDeclarationParents(sharedClass)

    val field = context.irFactory.buildSyntheticField {
      name = Name.identifier(Identifiers.Literals.REGISTRATION_FIELD)
      type = irBuiltIns.intType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
      isStatic = true
      origin = IrDeclarationOrigin.GeneratedByPlugin(JSModuleKey)
    }.apply {
      parent = sharedClass
      initializer = context.irFactory.createSyntheticExpressionBody(
        IrSyntheticCallImpl(irBuiltIns.intType, register.symbol),
      )
    }
    sharedClass.declarations += field
  }

  private fun makeStaticDefineFunction(sharedClass: IrClass): IrSimpleFunction {
    val define = sharedClass.addSyntheticFunction {
      name = Identifiers.Names.DEFINE_FUNCTION
      returnType = irBuiltIns.stringType.makeNullable()
      // PUBLIC, never internal: the JVM mangles an internal name and the registry looks it up by
      // the name the plugin chose.
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.FINAL
      origin = IrDeclarationOrigin.GeneratedByPlugin(JSModuleKey)
    }
    define.addValueParameter(
      Name.identifier(Identifiers.Literals.BUILDER_PARAMETER),
      symbols.classes.moduleBuilder.owner.defaultType,
      IrDeclarationOrigin.DEFINED,
    )
    return define
  }

  /**
   * ```
   * override fun define$ExpoModulesV2(builder: ModuleBuilder): String? {
   *   builder.function("add", AnyType(TypeDescriptor.Int, false), ..., returns = ..., methodName = "add")
   *   builder.property("count", AnyType(CommonDescriptors.INT_BOXED_NULL, false), true, "count")
   *   return "MathUtils"
   * }
   * ```
   */
  private fun generateDefine(
    moduleClass: IrClass,
    jsName: String,
    exported: List<Exported>,
    sharedClasses: List<ExportedSharedClass>,
  ) {
    val define = makeDefineFunction(moduleClass)
    val builder = define.parameters.single { it.kind == IrParameterKind.Regular }

    val body = context.irFactory.createSyntheticBlockBody()
    for (e in exported) {
      body.statements += when (e) {
        is ExportedFunction -> declareFunction(e, builder.get())
        is ExportedProperty -> declareProperty(e, builder.get())
      }
    }
    for (sharedClass in sharedClasses) {
      body.statements += declareSharedClass(sharedClass, builder.get())
    }
    body.statements += IrSyntheticReturnImpl(
      type = irBuiltIns.nothingType,
      returnTargetSymbol = define.symbol,
      value = poet.string(jsName),
    )
    define.body = body
    define.patchDeclarationParents(moduleClass)
  }

  private fun declareFunction(exportedFunction: ExportedFunction, builder: IrExpression): IrExpression {
    val anyTypeType = symbols.classes.anyType.owner.defaultType
    val arguments = mutableListOf(
      poet.string(exportedFunction.jsName),
      IrSyntheticVarargImpl(
        type = irBuiltIns.arrayClass.typeWith(anyTypeType),
        varargElementType = anyTypeType,
        elements = exportedFunction.arguments.map(::anyTypeOf),
      ),
      anyTypeOf(exportedFunction.result),
      poet.string(methodNameOf(exportedFunction)),
    )

    if (exportedFunction.isAsync) {
      arguments += poet.boolean(true)
    }

    return callOn(
      function = symbols.functions.builderFunction,
      receiver = builder,
      arguments = arguments,
      returnType = irBuiltIns.unitType,
    )
  }

  /** `builder.sharedClass("Klass", Klass::class.java, AnyType(...), ...)` */
  private fun declareSharedClass(
    exported: ExportedSharedClass,
    builder: IrExpression,
  ): IrExpression {
    val anyTypeType = symbols.classes.anyType.owner.defaultType
    return callOn(
      function = symbols.functions.builderSharedClass,
      receiver = builder,
      arguments = listOf(
        poet.string(exported.jsName),
        poet.javaClass(exported.sharedClass.defaultType),
        IrSyntheticVarargImpl(
          type = irBuiltIns.arrayClass.typeWith(anyTypeType),
          varargElementType = anyTypeType,
          elements = exported.arguments.map(::anyTypeOf),
        ),
        poet.string(exported.trampolineName),
      ),
      returnType = irBuiltIns.unitType,
    )
  }

  private fun declareProperty(exportProperty: ExportedProperty, builder: IrExpression): IrExpression =
    callOn(
      function = symbols.functions.builderProperty,
      receiver = builder,
      arguments = listOf(
        poet.string(exportProperty.jsName),
        anyTypeOf(exportProperty.getterPlan),
        poet.boolean(exportProperty.property.isVar),
        poet.string(propertyNameOf(exportProperty)),
        anyTypeOf(exportProperty.setterPlan ?: exportProperty.getterPlan),
      ),
      returnType = irBuiltIns.unitType,
    )

  /** `AnyType(descriptor, useBuffer)` */
  private fun anyTypeOf(plan: ValuePlan): IrExpression =
    poet.constructorCall(
      constructor = symbols.constructors.anyType,
      type = symbols.classes.anyType.owner.defaultType,
      arguments = listOf(poet.descriptorFor(plan.type), poet.boolean(plan.buffered)),
    )

  /** The JVM method the bridge resolves: the user's own when no trampoline stands in front of it. */
  private fun methodNameOf(export: ExportedFunction): String =
    export.trampolineName ?: export.function.name.asString()

  /** `ModuleBuilder` derives the JVM accessor names from this, including the `is` prefix rule. */
  private fun propertyNameOf(export: ExportedProperty): String =
    export.trampolineBase ?: export.property.name.asString()
}
