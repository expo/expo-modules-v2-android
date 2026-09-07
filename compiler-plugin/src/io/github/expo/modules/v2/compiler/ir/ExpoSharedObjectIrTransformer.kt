package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.Identifiers
import io.github.expo.modules.v2.compiler.JSModuleKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.Name

internal class ExpoSharedObjectIrTransformer(
  private val context: IrPluginContext,
  private val symbols: SymbolFinder,
  private val poet: TypeDescriptorPoet,
) : IrVisitorVoid() {
  private val irBuiltIns = context.irBuiltIns
  private val policy = TransportPolicy(context, symbols)
  private val members = ExportedMembers(policy)
  private val constructors = SharedClassExports(policy)
  private val trampolines = ExportedTrampolines(context, symbols, poet)
  private val definition = ModuleDefinitionPoet(context, symbols, poet)

  override fun visitElement(element: IrElement) {
    element.acceptChildrenVoid(this)
  }

  override fun visitClass(declaration: IrClass) {
    declaration.acceptChildrenVoid(this)

    val annotation =
      declaration.getAnnotation(Identifiers.FqNames.SHARED_OBJECT_ANNOTATION) ?: return
    val jsName = annotation.stringArgument(Identifiers.Names.ARG_NAME)
      ?.takeIf { it.isNotEmpty() }
      ?: declaration.name.asString()

    val exported = members.of(declaration)
    trampolines.generate(declaration, exported)
    constructors.constructorOf(declaration)?.let {
      trampolines.constructor(declaration, it)
    }

    val define = staticDefine(declaration)
    definition.fill(define = define, owner = declaration, jsName = jsName, exported = exported)
    registerOnLoad(declaration, define, jsName)
  }

  /**
   * `static fun define$ExpoModulesV2(builder: ModuleBuilder): String?`
   *
   * Public, never internal: the JVM mangles an internal name and the registry looks it up by the
   * name the plugin chose.
   */
  private fun staticDefine(sharedClass: IrClass): IrSimpleFunction {
    val define = sharedClass.addSyntheticFunction {
      name = Identifiers.Names.DEFINE_FUNCTION
      returnType = irBuiltIns.stringType.makeNullable()
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
   * A private static field whose initializer registers the class, so loading it is what puts the
   * class object within JavaScript's reach.
   */
  private fun registerOnLoad(sharedClass: IrClass, define: IrSimpleFunction, jsName: String) {
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
}
