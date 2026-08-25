package expo.modules.v2.compiler.ir

import expo.modules.v2.compiler.Identifiers
import expo.modules.v2.compiler.JSModuleKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

internal class TrampolinePoet(
  private val context: IrPluginContext,
  private val symbols: SymbolFinder,
  private val poet: TypeDescriptorPoet,
) {
  private val irBuiltIns = context.irBuiltIns

  /**
   * A trampoline body runs on every call, so an allocating descriptor is hoisted into a field of the
   * module class. It is static: a `TypeDescriptor` says nothing about the module instance, and a
   * module registered once per runtime would otherwise re-resolve every converter for each one.
   */
  private val descriptors = DescriptorFields(
    context, symbols, poet, JSModuleKey, isStatic = true,
  )

  fun functionTrampoline(
    moduleClass: IrClass,
    name: String,
    target: IrSimpleFunction,
    arguments: List<ValuePlan>,
    result: ValuePlan,
  ): IrSimpleFunction {
    val isAsync = target.isSuspend
    val trampoline = declare(
      moduleClass,
      name,
      if (isAsync) irBuiltIns.unitType else returnTypeOf(result),
    )
    val slots = arguments.filter { !it.buffered }.map { plan ->
      trampoline.addValueParameter(
        Name.identifier("p${arguments.indexOf(plan)}"),
        plan.jniType,
        IrDeclarationOrigin.DEFINED,
      )
    }
    val payloadLength = if (arguments.any { it.buffered }) {
      trampoline.addValueParameter(
        Name.identifier("payloadLength"),
        irBuiltIns.intType,
        IrDeclarationOrigin.DEFINED,
      )
    } else {
      null
    }
    // Last, after payloadLength - the order `HostFunctionSpec::functionSignature` builds.
    val promise = if (isAsync) {
      trampoline.addValueParameter(
        Name.identifier(Identifiers.Literals.PROMISE_PARAMETER),
        symbols.classes.promise.owner.defaultType,
        IrDeclarationOrigin.DEFINED,
      )
    } else {
      null
    }

    val statements = mutableListOf<IrStatement>()
    val locals = readBufferedArguments(moduleClass, trampoline, arguments, payloadLength, statements)

    // Rebuild the declared order: a buffered argument comes from its local, the rest from their slots.
    var nextSlot = 0
    val callArguments = arguments.map { plan ->
      if (plan.buffered) {
        locals.getValue(plan).get()
      } else {
        fromSlot(moduleClass, slots[nextSlot++], plan)
      }
    }

    val call = callOn(
      target.symbol,
      receiver = trampoline.thisReceiver().get(),
      arguments = callArguments,
      returnType = target.returnType,
    )
    if (promise != null) {
      statements += startCoroutine(moduleClass, trampoline, promise, call, result)
    } else {
      finishWithResult(moduleClass, trampoline, call, result, statements)
    }

    trampoline.body = context.irFactory.createSyntheticBlockBody().apply {
      this.statements += statements
    }
    trampoline.patchDeclarationParents(moduleClass)
    return trampoline
  }

  /**
   * `promise.launch(<descriptor>, <buffered>) { <call> }` — the entire async half of the emitter.
   *
   * Every buffered argument has already been read into a local by the time this runs, which is what
   * makes the shared binary buffer safe: it is thread-local and reused per call, and the coroutine
   * may resume on another thread entirely.
   *
   * The block is a real `suspend` lambda — an [IrFunctionExpressionImpl] over a function with
   * `isSuspend = true`. `IrGenerationExtension` runs before the JVM backend's coroutine lowering,
   * so the state machine is built for us. Its result type is `Any?`, so a primitive return boxes
   * here and `Promise` converts it with the descriptor it was handed.
   */
  private fun startCoroutine(
    moduleClass: IrClass,
    owner: IrSimpleFunction,
    promise: IrValueParameter,
    call: IrExpression,
    result: ValuePlan,
  ): IrExpression {
    val block = context.irFactory.buildSyntheticFun {
      name = SpecialNames.ANONYMOUS
      returnType = irBuiltIns.anyNType
      visibility = DescriptorVisibilities.LOCAL
      modality = Modality.FINAL
      isSuspend = true
      origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
    }.apply {
      parent = owner
      body = context.irFactory.createSyntheticBlockBody().apply {
        this.statements += IrSyntheticReturnImpl(irBuiltIns.nothingType, symbol, call)
      }
    }

    return callOn(
      symbols.functions.promiseLaunch,
      receiver = promise.get(),
      arguments = listOf(
        descriptors.read(moduleClass, result.type),
        poet.boolean(result.buffered),
        IrSyntheticFunctionExpressionImpl(
          irBuiltIns.suspendFunctionN(0).typeWith(irBuiltIns.anyNType),
          block,
          IrStatementOrigin.LAMBDA,
        ),
      ),
    )
  }

  // --- properties ------------------------------------------------------------------------------

  fun propertyGetter(
    moduleClass: IrClass,
    name: String,
    property: IrProperty,
    plan: ValuePlan,
  ): IrSimpleFunction {
    val getter = declare(moduleClass, name, returnTypeOf(plan))
    val read = callOn(
      requireNotNull(property.getter).symbol,
      receiver = getter.thisReceiver().get(),
      returnType = requireNotNull(property.getter).returnType,
    )
    val statements = mutableListOf<IrStatement>()
    finishWithResult(moduleClass, getter, read, plan, statements)

    getter.body = context.irFactory.createSyntheticBlockBody().apply {
      this.statements += statements
    }
    getter.patchDeclarationParents(moduleClass)
    return getter
  }

  fun propertySetter(
    moduleClass: IrClass,
    name: String,
    property: IrProperty,
    plan: ValuePlan,
  ): IrSimpleFunction {
    val setter = declare(moduleClass, name, irBuiltIns.unitType)
    val parameter = setter.addValueParameter(
      Name.identifier(if (plan.buffered) "payloadLength" else "value"),
      if (plan.buffered) irBuiltIns.intType else plan.jniType,
      IrDeclarationOrigin.DEFINED,
    )

    val statements = mutableListOf<IrStatement>()
    val value = if (plan.buffered) {
      val locals = readBufferedArguments(
        moduleClass, setter, listOf(plan), parameter, statements,
      )
      locals.getValue(plan).get()
    } else {
      fromSlot(moduleClass, parameter, plan)
    }

    statements += callOn(
      requireNotNull(property.setter).symbol,
      receiver = setter.thisReceiver().get(),
      arguments = listOf(value),
      returnType = irBuiltIns.unitType,
    )

    setter.body = context.irFactory.createSyntheticBlockBody().apply {
      this.statements += statements
    }
    setter.patchDeclarationParents(moduleClass)
    return setter
  }

  // --- pieces ----------------------------------------------------------------------------------

  private fun declare(moduleClass: IrClass, name: String, returnType: IrType): IrSimpleFunction {
    val function = moduleClass.addSyntheticFunction {
      this.name = Name.identifier(name)
      this.returnType = returnType
      // PUBLIC, never internal: the JVM mangles an internal name and GetMethodID would miss it.
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.FINAL
      origin = IrDeclarationOrigin.GeneratedByPlugin(JSModuleKey)
    }
    function.createDispatchReceiverParameter()
    return function
  }

  private fun returnTypeOf(plan: ValuePlan): IrType =
    if (plan.buffered) irBuiltIns.intType else plan.jniType

  /**
   * Declares a local per buffered value and fills them inside `try { } finally { args.finish() }`.
   *
   * The reads happen in declared order, which is the order the payload was written in. `finish` has
   * to run even when a read throws: a half-consumed payload must not leave references behind.
   */
  private fun readBufferedArguments(
    moduleClass: IrClass,
    owner: IrSimpleFunction,
    plans: List<ValuePlan>,
    payloadLength: IrValueParameter?,
    statements: MutableList<IrStatement>,
  ): Map<ValuePlan, IrVariable> {
    val buffered = plans.filter { it.buffered }
    if (buffered.isEmpty()) {
      return emptyMap()
    }
    val length = requireNotNull(payloadLength) {
      "a buffered value needs a payloadLength parameter"
    }

    val args = buildSyntheticVariable(
      parent = owner,
      origin = IrDeclarationOrigin.DEFINED,
      name = Name.identifier("args"),
      type = symbols.classes.trampolineArguments.owner.defaultType,
    ).apply {
      initializer = callStatic(
        symbols.functions.arguments,
        symbols.classes.trampoline,
        listOf(length.get()),
        returnType = symbols.classes.trampolineArguments.owner.defaultType,
      )
    }
    statements += args

    val locals = LinkedHashMap<ValuePlan, IrVariable>()
    val reads = mutableListOf<IrStatement>()
    for (plan in buffered) {
      val local = buildSyntheticVariable(
        parent = owner,
        origin = IrDeclarationOrigin.DEFINED,
        name = Name.identifier("v${plans.indexOf(plan)}"),
        type = plan.type,
        isVar = true,
      )
      locals[plan] = local
      statements += local
      reads += IrSyntheticSetValueImpl(
        irBuiltIns.unitType, local.symbol,
        readFromBuffer(moduleClass, args, plan),
      )
    }

    statements += IrSyntheticTryImpl(irBuiltIns.unitType).apply {
      tryResult = IrSyntheticBlockImpl(irBuiltIns.unitType, statements = reads)
      finallyExpression = callOn(symbols.functions.finish, args.get(), returnType = irBuiltIns.unitType)
    }
    return locals
  }

  /**
   * One buffered value. A passthrough leaf uses its typed reader, which skips a converter lookup per
   * call; everything else goes through the descriptor-driven `next(schema)`.
   */
  private fun readFromBuffer(
    moduleClass: IrClass,
    args: IrVariable,
    plan: ValuePlan,
  ): IrExpression {
    plan.bufferReader?.let { name ->
      val reader = symbols.functions.argumentsReader(name)
      return coerce(
        callOn(reader, args.get(), returnType = reader.owner.returnType),
        plan.type,
      )
    }
    return callOn(
      symbols.functions.next,
      args.get(),
      arguments = listOf(descriptors.read(moduleClass, plan.type)),
      returnType = plan.type,
      typeArguments = listOf(plan.type),
    )
  }

  /** A value that kept its JNI slot, converted to what the user's declaration says. */
  private fun fromSlot(
    moduleClass: IrClass,
    parameter: IrValueParameter,
    plan: ValuePlan,
  ): IrExpression {
    if (plan.passthrough) {
      return coerce(parameter.get(), plan.type)
    }
    return coerce(
      callStatic(
        symbols.functions.fromJni,
        symbols.classes.bridge,
        listOf(parameter.get(), descriptors.read(moduleClass, plan.type)),
      ),
      plan.type,
    )
  }

  /** Appends the call and, unless the export returns `Unit`, the return of its bridge form. */
  private fun finishWithResult(
    moduleClass: IrClass,
    owner: IrSimpleFunction,
    call: IrExpression,
    plan: ValuePlan,
    statements: MutableList<IrStatement>,
  ) {
    if (plan.kind == ValueKind.UNIT) {
      statements += call
      return
    }

    val result = when {
      plan.buffered -> callStatic(
        symbols.functions.writeResult,
        symbols.classes.trampoline,
        listOf(call, descriptors.read(moduleClass, plan.type)),
        returnType = irBuiltIns.intType,
      )
      plan.passthrough -> coerce(call, plan.jniType)
      else -> coerce(
        callStatic(
          symbols.functions.toJni,
          symbols.classes.bridge,
          listOf(call, descriptors.read(moduleClass, plan.type)),
        ),
        plan.jniType,
      )
    }
    statements += IrSyntheticReturnImpl(irBuiltIns.nothingType, owner.symbol, result)
  }

  // --- helpers ---------------------------------------------------------------------------------

  private fun IrSimpleFunction.thisReceiver(): IrValueParameter =
    parameters.single { it.kind == IrParameterKind.DispatchReceiver }

  /**
   * A cast to [target], emitted only when the expression is not already that exact type — which is
   * the normal case for a `Bridge` result (`Any?`) and for a nullable slot feeding a non-null
   * declaration.
   */
  private fun coerce(value: IrExpression, target: IrType): IrExpression {
    val source = value.type
    if (source.classOrNull == target.classOrNull &&
      source.isMarkedNullable() == target.isMarkedNullable()
    ) {
      return value
    }
    return IrSyntheticTypeOperatorCallImpl(target, IrTypeOperator.CAST, target, value)
  }
}
