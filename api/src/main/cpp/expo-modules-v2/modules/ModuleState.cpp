#include <expo-modules-v2/modules/ModuleState.h>

#include <utility>

#include <kolibri/env.h>

namespace expo::modules::v2 {
  namespace {
    kolibri::GlobalRef<kolibri::JClass> classOf(JNIEnv* env, const jobject instance) {
      const kolibri::Ref<> local = kolibri::Ref<>::adopt(env, env->GetObjectClass(instance));
      return kolibri::GlobalRef<kolibri::JClass>::make(env, local.get());
    }
  } // namespace

  ModuleState::ModuleState(
    JNIEnv* env,
    const objects::ObjectId::Value objectId,
    kolibri::GlobalRef<> instance
  ) : ObjectState(kKind, objectId, std::move(instance)),
      javaClass_(classOf(env, this->instance())) {
  }

  ModuleState::~ModuleState() {
    // The last owner may be a JavaScript object Hermes finalizes on a JNI-detached GC thread. The
    // class ref dies with this state's members, before the base destructor gets to attach.
    kolibri::getEnv();
  }
} // namespace expo::modules::v2
