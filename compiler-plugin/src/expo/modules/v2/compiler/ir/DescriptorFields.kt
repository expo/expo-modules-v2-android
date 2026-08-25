package expo.modules.v2.compiler.ir

import expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.Name

internal class DescriptorFields(
  private val context: IrPluginContext,
  private val symbols: SymbolFinder,
  private val poet: TypeDescriptorPoet,
  private val key: GeneratedDeclarationKey,
  private val isStatic: Boolean,
) {
  private val fields = mutableMapOf<IrClass, MutableMap<String, IrField>>()

  fun read(owner: IrClass, type: IrType, receiver: IrValueParameter? = null): IrExpression {
    require(isStatic == (receiver == null)) {
      "a ${if (isStatic) "static" else "non-static"} descriptor field " +
        "${if (isStatic) "cannot" else "has to"} be read through a receiver"
    }

    val descriptor = poet.descriptorFor(type)
    if (descriptor is IrGetObjectValue || descriptor is IrGetField) {
      return descriptor
    }

    val field = fieldFor(owner, type, descriptor)
    return IrSyntheticGetFieldImpl(
      symbol = field.symbol,
      type = field.type,
      receiver = receiver?.let { IrSyntheticGetValueImpl(it.type, it.symbol) },
    )
  }

  private fun fieldFor(owner: IrClass, type: IrType, descriptor: IrExpression): IrField {
    val perClass = fields.getOrPut(owner) { mutableMapOf() }
    val key = type.render()
    perClass[key]?.let { return it }

    val field = context
      .irFactory
      .buildSyntheticField {
        name = Name.identifier(Identifiers.Literals.DESCRIPTOR_FIELD_PREFIX + perClass.size)
        this.type = symbols.classes.typeDescriptor.owner.defaultType
        visibility = DescriptorVisibilities.PRIVATE
        isFinal = true
        isStatic = this@DescriptorFields.isStatic
        origin = IrDeclarationOrigin.GeneratedByPlugin(this@DescriptorFields.key)
      }.apply {
        parent = owner
        initializer = context.irFactory.createSyntheticExpressionBody(descriptor)
      }
    owner.declarations.add(perClass.size, field)
    perClass[key] = field
    return field
  }
}
