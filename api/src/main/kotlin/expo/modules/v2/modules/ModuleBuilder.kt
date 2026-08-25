package expo.modules.v2.modules

import expo.modules.v2.args.Trampoline
import expo.modules.v2.types.AnyType

class ModuleBuilder internal constructor() {
  internal val functions = mutableListOf<ModuleFunctionDefinition>()
  internal val properties = mutableListOf<ModulePropertyDefinition>()

  fun function(
    jsName: String,
    vararg argTypes: AnyType,
    returns: AnyType,
    methodName: String = jsName,
    isAsync: Boolean = false,
  ) {
    require(argTypes.size <= Trampoline.MAX_ARGUMENTS) {
      "Function '$jsName' declares ${argTypes.size} arguments; at most " +
        "${Trampoline.MAX_ARGUMENTS} are supported"
    }
    requireAvailableExportName(jsName)

    functions.add(
      ModuleFunctionDefinition(
        jsName = jsName,
        methodName = methodName,
        argTypes = Array(argTypes.size) { argTypes[it].codes.values },
        returnType = returns.codes.values,
        isAsync = isAsync,
      ),
    )
  }

  fun property(
    jsName: String,
    type: AnyType,
    mutable: Boolean = false,
    propertyName: String = jsName,
    setterType: AnyType = type,
  ) {
    requireAvailableExportName(jsName)

    val getterName = if (hasIsPrefix(propertyName)) {
      propertyName
    } else {
      "get${propertyName.accessorSuffix()}"
    }

    val setterName = if (mutable) {
      if (hasIsPrefix(propertyName)) {
        "set${propertyName.substring(2)}"
      } else {
        "set${propertyName.accessorSuffix()}"
      }
    } else {
      null
    }

    properties.add(
      ModulePropertyDefinition(
        jsName = jsName,
        getterName = getterName,
        setterName = setterName,
        getterType = type.codes.values,
        setterType = if (setterName != null) {
          setterType.codes.values
        } else {
          null
        },
      ),
    )
  }

  private fun requireAvailableExportName(name: String) {
    require(functions.none { it.jsName == name } && properties.none { it.jsName == name }) {
      "Export '$name' is already declared in this module or shared-object class"
    }
  }

  private fun hasIsPrefix(propertyName: String): Boolean =
    propertyName.length > 2 && propertyName.startsWith("is") && !propertyName[2].isLowerCase()

  private fun String.accessorSuffix(): String = replaceFirstChar { character ->
    if (character.isLowerCase()) {
      character.titlecase()
    } else {
      character.toString()
    }
  }
}
