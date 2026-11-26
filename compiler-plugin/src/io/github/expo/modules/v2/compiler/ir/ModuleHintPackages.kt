package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.ModuleHintKey
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Records [packageName] on every module hint this compilation generated, as
 * `@ModuleHintPackage(packageName)`. The hint ships in the library's classes, so the package travels
 * with the module to every compilation that later finds the hint on its classpath.
 *
 * The annotation is added here, after the frontend wrote the class's metadata, so the metadata does
 * not flag the class as annotated and a compiler that loads the hint from the classpath does not
 * show it. [ModuleHintScanner.packageOf] reads it from the class file instead.
 */
internal fun markHintPackages(
  moduleFragment: IrModuleFragment,
  symbols: SymbolFinder,
  poet: TypeDescriptorPoet,
  packageName: String,
) {
  val annotation = symbols.classes.moduleHintPackage
  moduleFragment.acceptChildrenVoid(object : IrVisitorVoid() {
    override fun visitElement(element: IrElement) {
      element.acceptChildrenVoid(this)
    }

    override fun visitClass(declaration: IrClass) {
      if ((declaration.origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey == ModuleHintKey) {
        declaration.addAnnotation(
          annotation.owner.defaultType,
          annotation.constructors.single(),
          listOf(poet.string(packageName)),
        )
      }
      declaration.acceptChildrenVoid(this)
    }
  })
}
