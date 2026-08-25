package expo.modules.v2.modules

/**
 * The base class of every module a runtime exposes under `expo.modules.<name>`.
 *
 * Annotate a subclass — and each function or property to export — with
 * [expo.modules.v2.annotations.JS], then hand the instance to [ModuleRegistry.register]. Modules
 * whose exports the plugin cannot express register through
 * [ModuleRegistry.register(name, module, build)][ModuleRegistry.register] instead.
 */
abstract class Module {
  /**
   * Fills [builder] with this module's exports and returns the name JavaScript sees, or `null` when
   * the class is not annotated with [expo.modules.v2.annotations.JS].
   *
   * Overridden by the compiler plugin, in the backend only — so it is invisible to the IDE and to
   * any source that tries to call it. `ModuleRegistry.register(module)` is the way in.
   *
   * Public rather than `internal` on purpose: `internal` appends a `$<module>` suffix to the JVM
   * name, which would break the override from any other module. The `$` in the name is what keeps
   * it un-typeable from Kotlin source without backticks.
   */
  open fun `define$ExpoModulesV2`(builder: ModuleBuilder): String? = null
}
