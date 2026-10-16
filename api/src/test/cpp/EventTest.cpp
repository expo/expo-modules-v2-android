#include "Fixture.h"

#include <atomic>
#include <future>
#include <thread>

namespace {
  struct Change {
    int value = 0;
    std::string label;
  };
  EXPO_RECORD(Change, value, label)

  class Player : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Player>& cls) {
      cls.constructor<>();
      cls.function("play", &Player::play);
    }

    int play() {
      onStateChange.emit("playing");
      return 1;
    }

    int starts = 0;
    int stops = 0;

    cpp::Event<std::string> onStateChange{
      this,
      "stateChange",
      {.onStartObserving = [this] { starts++; }, .onStopObserving = [this] { stops++; }},
    };
  };

  class Watcher : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("fire", [this](int value) {
        onChanged.emit(Change{value, "v" + std::to_string(value)});
        return value;
      });
      def.function("emitCount", [this](int n) { onCount.emit(n); });
      def.function("create", [this] { return last = std::make_shared<Player>(); });
      def.sharedClass<Player>();
    }

    std::atomic<int> starts{0};
    std::atomic<int> stops{0};
    std::shared_ptr<Player> last;

    cpp::Event<Change> onChanged{
      this,
      "changed",
      {.onStartObserving = [this] { starts++; }, .onStopObserving = [this] { stops++; }},
    };
    cpp::Event<int> onCount{this, "count"};
    cpp::Event<std::string> onText{this, "text"};
    cpp::Event<std::vector<std::string>> onNames{this, "names"};
    cpp::Event<std::shared_ptr<Player>> onPlayer{this, "player"};
    cpp::Event<int, std::string> onPair{this, "pair"};
    cpp::Event<> onPing{this, "ping"};
  };

  /** A module with no events at all still carries the emitter members. */
  class Silent : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override { def.function("noop", [] {}); }
  };

  struct Env : JsEnv {
    std::shared_ptr<Watcher> watcher;

    Env() {
      watcher = context().emplace<Watcher>("Watcher");
      exec("globalThis.seen = [];");
    }
  };
} // namespace

TEST(event_an_emit_inside_a_host_function_reaches_the_listener_before_the_call_returns) {
  Env env;
  CHECK_EQ(
    env.eval(
      "expo.modules.Watcher.addListener('changed', (e) => seen.push(e.value + ':' + e.label));"
      "expo.modules.Watcher.fire(2) + ' -> ' + seen.join()"
    ),
    "2 -> 2:v2"
  );
}

TEST(event_a_listener_is_called_with_the_emitter_as_this) {
  Env env;
  CHECK_EQ(
    env.eval(
      "expo.modules.Watcher.addListener('count', function () { globalThis.self = this; });"
      "expo.modules.Watcher.emitCount(1); self === expo.modules.Watcher"
    ),
    "true"
  );
}

TEST(event_an_emit_on_the_js_thread_outside_any_call_is_delivered_at_once) {
  Env env;
  env.exec("expo.modules.Watcher.addListener('count', (n) => seen.push(n));");
  env.watcher->onCount.emit(5);
  env.watcher->onCount(6);
  CHECK_EQ(env.eval("seen.join()"), "5,6");
}

TEST(event_an_emit_from_another_thread_is_delivered_when_the_js_thread_runs) {
  Env env;
  env.exec("expo.modules.Watcher.addListener('count', (n) => seen.push(n));");

  std::thread worker([&] { env.watcher->onCount.emit(7); });
  worker.join();

  // Posted, not delivered: the payload converts on the JS thread, when it drains its jobs.
  CHECK_EQ(env.eval("seen.length"), "0");
  env.pumpUntil("seen.length > 0");
  CHECK_EQ(env.eval("seen.join()"), "7");
}

TEST(event_every_payload_kind_reaches_javascript) {
  Env env;
  env.exec(
    "expo.modules.Watcher.addListener('text', (s) => seen.push(s.length));"
    "expo.modules.Watcher.addListener('names', (l) => seen.push(l.join('|')));"
    "expo.modules.Watcher.addListener('player', (p) => seen.push(p === globalThis.p));"
    "globalThis.p = expo.modules.Watcher.create();"
  );

  env.watcher->onText.emit("abc");
  env.watcher->onText.emit(std::string(300000, 'x'));
  env.watcher->onNames.emit({"a", "b"});
  env.watcher->onPlayer.emit(env.watcher->last);

  CHECK_EQ(env.eval("seen.join()"), "3,300000,a|b,true");
}

TEST(event_a_payload_may_have_several_values_or_none) {
  Env env;
  env.exec(
    "expo.modules.Watcher.addListener('pair', (...args) => seen.push(args.join('+')));"
    "expo.modules.Watcher.addListener('ping', (...args) => seen.push('ping' + args.length));"
  );
  env.watcher->onPair.emit(4, "four");
  env.watcher->onPing.emit();
  CHECK_EQ(env.eval("seen.join()"), "4+four,ping0");
}

TEST(event_listeners_are_added_counted_and_removed_as_on_an_event_emitter) {
  Env env;
  CHECK_EQ(
    env.eval(
      "const w = expo.modules.Watcher;"
      "const a = () => seen.push('a');"
      "const b = () => seen.push('b');"
      "const out = [];"
      "const sub = w.addListener('count', a);"
      "out.push(w.listenerCount('count'));"
      "w.addListener('count', b);"
      "out.push(w.listenerCount('count'));"
      "sub.remove();"
      "out.push(w.listenerCount('count'));"
      "w.removeListener('count', b);"
      "out.push(w.listenerCount('count'));"
      "w.addListener('count', a);"
      "out.push(w.listenerCount('count'));"
      "w.removeAllListeners('count');"
      "out.push(w.listenerCount('count'));"
      "out.join();"
    ),
    "1,2,1,0,1,0"
  );
}

TEST(event_a_throwing_listener_stops_neither_the_others_nor_the_emitter) {
  Env env;
  env.exec(
    "expo.modules.Watcher.addListener('count', () => { throw new Error('boom'); });"
    "expo.modules.Watcher.addListener('count', (n) => seen.push(n));"
  );
  env.watcher->onCount.emit(3);
  CHECK_EQ(env.eval("seen.join()"), "3");
}

TEST(event_a_listener_that_changes_the_listeners_changes_the_next_emit_not_this_one) {
  Env env;
  CHECK_EQ(
    env.eval(
      "const w = expo.modules.Watcher;"
      "const late = () => seen.push('late');"
      "const first = () => { seen.push('first'); w.addListener('count', late); w.removeListener('count', second); };"
      "const second = () => seen.push('second');"
      "w.addListener('count', first); w.addListener('count', second);"
      "w.emitCount(1); seen.join()"
    ),
    "first,second"
  );
  CHECK_EQ(env.eval("seen.length = 0; expo.modules.Watcher.emitCount(1); seen.join()"), "first,late");
}

TEST(event_emit_from_javascript_calls_the_listeners_with_its_arguments) {
  Env env;
  CHECK_EQ(
    env.eval(
      "const w = expo.modules.Watcher;"
      "let self;"
      "w.addListener('count', function (...args) { self = this; seen.push(args.join('+')); });"
      "w.addListener('text', () => seen.push('text'));"
      "const result = w.emit('count', 1, 'extra');"
      "[seen.join(), self === w, result === undefined].join('|');"
    ),
    "1+extra|true|true"
  );
}

TEST(event_emit_on_a_shared_object_calls_only_that_objects_listeners) {
  Env env;
  CHECK_EQ(
    env.eval(
      "const a = expo.modules.Watcher.create();"
      "const b = expo.modules.Watcher.create();"
      "a.addListener('stateChange', (state) => seen.push(state));"
      "b.addListener('stateChange', () => seen.push('wrong object'));"
      "a.emit('stateChange', 'paused');"
      "seen.join();"
    ),
    "paused"
  );
}

TEST(event_emit_without_listeners_does_nothing_and_does_not_start_observing) {
  Env env;
  CHECK_EQ(env.eval("String(expo.modules.Watcher.emit('changed', {}))"), "undefined");
  CHECK_EQ(env.watcher->starts.load(), 0);
  CHECK_EQ(env.watcher->stops.load(), 0);
}

TEST(event_an_unknown_event_name_is_rejected_by_every_member) {
  Env env;
  env.context().emplace<Silent>("Silent");

  for (const char* call: {
         "addListener('nope', () => {})",
         "removeListener('nope', () => {})",
         "removeAllListeners('nope')",
         "listenerCount('nope')",
         "emit('nope')",
       }) {
    const std::string error = env.errorOf(std::string("expo.modules.Watcher.") + call);
    CHECK_CONTAINS(error, "'nope' is not an event");
    CHECK_CONTAINS(error, "'changed'");
    CHECK_CONTAINS(error, "'count'");
  }

  // A module without events has the members, and says so when asked for anything.
  CHECK_CONTAINS(env.errorOf("expo.modules.Silent.addListener('x', () => {})"), "declares: none");
}

TEST(event_member_arguments_are_checked) {
  Env env;
  CHECK_CONTAINS(env.errorOf("expo.modules.Watcher.addListener()"), "expects an event name as its first argument");
  CHECK_CONTAINS(env.errorOf("expo.modules.Watcher.addListener('count')"), "expects a listener function as its second argument");
  CHECK_CONTAINS(env.errorOf("expo.modules.Watcher.addListener('count', 5)"), "expects a listener function as its second argument");
}

TEST(event_a_member_called_on_something_else_is_rejected) {
  Env env;
  CHECK_CONTAINS(
    env.errorOf("expo.modules.Watcher.addListener.call({}, 'count', () => {})"),
    "called on something that is not a module or shared object"
  );
  CHECK_CONTAINS(
    env.errorOf("expo.modules.Watcher.addListener.call(undefined, 'count', () => {})"),
    "called on something that is not a module or shared object"
  );
}

TEST(event_one_emitter_prototype_serves_every_module_and_shared_object_of_a_runtime) {
  Env env;
  env.context().emplace<Silent>("Silent");

  CHECK_EQ(
    env.eval(
      "const emitter = Object.getPrototypeOf(expo.modules.Watcher);"
      "const p = expo.modules.Watcher.create();"
      "[Object.getPrototypeOf(expo.modules.Silent) === emitter,"
      " Object.getPrototypeOf(Object.getPrototypeOf(p)) === emitter,"
      " Object.getPrototypeOf(new expo.modules.Watcher.Player()) === Object.getPrototypeOf(p)].join()"
    ),
    "true,true,true"
  );
  CHECK_EQ(
    env.eval(
      "[expo.modules.Watcher.addListener === expo.modules.Watcher.create().addListener,"
      " expo.modules.Watcher.emit === expo.modules.Silent.emit].join()"
    ),
    "true,true"
  );
  // And the module's own exports stay its own.
  CHECK_EQ(
    env.eval("[expo.modules.Watcher.hasOwnProperty('fire'), expo.modules.Watcher.hasOwnProperty('addListener')].join()"),
    "true,false"
  );
}

TEST(event_the_emitter_members_are_not_enumerable) {
  Env env;
  CHECK_EQ(
    env.eval("[Object.keys(expo.modules.Watcher).includes('addListener'), typeof expo.modules.Watcher.addListener === 'function'].join()"),
    "false,true"
  );
  CHECK_EQ(
    env.eval("const p = expo.modules.Watcher.create(); const k = []; for (const n in p) k.push(n); k.includes('addListener')"),
    "false"
  );
}

TEST(event_the_observing_hooks_fire_on_the_first_listener_and_after_the_last) {
  Env env;
  Watcher& watcher = *env.watcher;

  env.exec("globalThis.sub = expo.modules.Watcher.addListener('changed', () => {});");
  CHECK_EQ(watcher.starts.load(), 1);
  CHECK_EQ(watcher.stops.load(), 0);
  CHECK(watcher.onChanged.isObserved());

  env.exec("globalThis.fn = () => {}; expo.modules.Watcher.addListener('changed', fn);");
  CHECK_EQ(watcher.starts.load(), 1);  // a second listener starts nothing

  env.exec("sub.remove();");
  CHECK_EQ(watcher.stops.load(), 0);  // one listener is left

  env.exec("expo.modules.Watcher.removeListener('changed', fn);");
  CHECK_EQ(watcher.starts.load(), 1);
  CHECK_EQ(watcher.stops.load(), 1);
  CHECK(!watcher.onChanged.isObserved());

  env.exec("expo.modules.Watcher.addListener('changed', fn); expo.modules.Watcher.removeAllListeners('changed');");
  CHECK_EQ(watcher.starts.load(), 2);
  CHECK_EQ(watcher.stops.load(), 2);

  // Other events do not count.
  env.exec("expo.modules.Watcher.addListener('count', fn);");
  CHECK_EQ(watcher.starts.load(), 2);
  CHECK_EQ(watcher.stops.load(), 2);
}

TEST(event_removing_a_listener_that_was_never_added_does_nothing) {
  Env env;
  env.exec("expo.modules.Watcher.removeListener('changed', () => {});");
  CHECK_EQ(env.watcher->stops.load(), 0);
}

TEST(event_closing_a_runtime_that_observes_stops_the_observation) {
  auto context = cpp::Context::create(false);
  auto watcher = context->emplace<Watcher>("Watcher");
  {
    JsEnv env(context);
    env.exec("expo.modules.Watcher.addListener('changed', () => {});");
    CHECK_EQ(watcher->starts.load(), 1);
  }
  CHECK_EQ(watcher->stops.load(), 1);
  CHECK(!watcher->onChanged.isObserved());

  // And a later emit has nowhere to go, which is fine.
  watcher->onChanged.emit(Change{1, "gone"});
}

TEST(event_two_runtimes_observing_one_instance_both_receive_and_count_as_one_observer) {
  auto context = cpp::Context::create(false);
  auto watcher = context->emplace<Watcher>("Watcher");
  JsEnv first(context);
  JsEnv second(context);
  first.exec("globalThis.seen = []; globalThis.fn = (n) => seen.push('first:' + n); expo.modules.Watcher.addListener('count', fn);");
  second.exec("globalThis.seen = []; globalThis.fn = (n) => seen.push('second:' + n); expo.modules.Watcher.addListener('count', fn);");
  CHECK(watcher->onCount.isObserved());

  // Both runtimes live on this thread, so both deliveries are inline.
  watcher->onCount.emit(4);
  CHECK_EQ(first.eval("seen.join()"), "first:4");
  CHECK_EQ(second.eval("seen.join()"), "second:4");

  first.exec("expo.modules.Watcher.removeListener('count', fn);");
  CHECK(watcher->onCount.isObserved());  // the second runtime still observes
  watcher->onCount.emit(5);
  CHECK_EQ(first.eval("seen.join()"), "first:4");
  CHECK_EQ(second.eval("seen.join()"), "second:4,second:5");

  second.exec("expo.modules.Watcher.removeListener('count', fn);");
  CHECK(!watcher->onCount.isObserved());
}

TEST(event_a_runtime_on_another_thread_receives_an_emit_when_it_drains) {
  auto context = cpp::Context::create(false);
  auto watcher = context->emplace<Watcher>("Watcher");

  std::promise<void> listening;
  std::promise<std::string> result;
  std::thread worker([&] {
    JsEnv second(context);
    second.exec("globalThis.seen = []; expo.modules.Watcher.addListener('count', (n) => seen.push(n));");
    listening.set_value();
    second.pumpUntil("seen.length > 0");
    result.set_value(second.eval("seen.join()"));
  });

  listening.get_future().wait();
  // Not this thread's runtime, so the delivery is posted to the worker's scheduler.
  watcher->onCount.emit(9);

  CHECK_EQ(result.get_future().get(), "9");
  worker.join();
}

TEST(event_a_shared_object_emits_on_its_facade) {
  Env env;
  CHECK_EQ(
    env.eval(
      "globalThis.p = expo.modules.Watcher.create();"
      "p.addListener('stateChange', function (s) { seen.push((this === p) + ' ' + s); });"
      "p.play(); seen.join()"
    ),
    "true playing"
  );
  CHECK_EQ(env.watcher->last->starts, 1);

  // A second instance has its own listeners.
  CHECK_EQ(env.eval("new expo.modules.Watcher.Player().listenerCount('stateChange')"), "0");
  // The members live on the prototype, shared by every instance.
  CHECK_EQ(env.eval("p.addListener === new expo.modules.Watcher.Player().addListener"), "true");
}

TEST(event_releasing_a_shared_object_stops_its_observation_and_rejects_new_listeners) {
  Env env;
  env.exec(
    "globalThis.p = expo.modules.Watcher.create();"
    "p.addListener('stateChange', (s) => seen.push(s)); p.release();"
  );
  const std::shared_ptr<Player> player = env.watcher->last;
  CHECK_EQ(player->starts, 1);
  CHECK_EQ(player->stops, 1);

  // Nothing observes a released object, so an emit goes nowhere.
  player->onStateChange.emit("late");
  CHECK_EQ(env.eval("seen.length"), "0");

  CHECK_CONTAINS(env.errorOf("p.addListener('stateChange', () => {})"), "released");
}

TEST(event_a_facade_collected_with_listeners_attached_stops_its_observation) {
  Env env;
  // The script ends in a primitive: an `evaluate` result is a live handle, and one holding the
  // subscription would root the facade through the subscription's `remove`.
  env.exec("expo.modules.Watcher.create().addListener('stateChange', () => {}); 0");
  const std::shared_ptr<Player> player = env.watcher->last;
  CHECK_EQ(player->starts, 1);

  // Nothing in JavaScript holds the facade. The orphaned listeners go the next time the object is
  // looked up, which an emit does.
  for (int pass = 0; pass < 10 && player->stops == 0; pass++) {
    env.rt().instrumentation().collectGarbage("test");
    player->onStateChange.emit("nobody");
  }
  CHECK_EQ(player->stops, 1);

  player->onStateChange.emit("nobody");
  CHECK_EQ(player->stops, 1);  // an emit after the facade is gone is a plain no-op
}

TEST(event_an_event_name_may_not_be_declared_twice) {
  class Twice : public cpp::Module {
  public:
    cpp::Event<int> first{this, "same"};
    cpp::Event<int> second{this, "same"};
  };

  CHECK_THROWS(std::make_shared<Twice>());
}

namespace {
  /** Collects until [watcher] is gone, which takes a few passes when the collector is incremental. */
  bool collectedAway(JsEnv& env, const std::weak_ptr<cpp::SharedObject>& watcher) {
    for (int pass = 0; pass < 10 && !watcher.expired(); pass++) {
      env.rt().instrumentation().collectGarbage("test");
    }
    return watcher.expired();
  }
} // namespace

TEST(event_a_listener_that_mentions_its_emitter_does_not_keep_it_alive) {
  Env env;
  // The listener closes over `p`, and `p` holds the listener: a cycle JavaScript can collect, as
  // long as the table of listeners is not a root of its own.
  env.exec("{ const p = expo.modules.Watcher.create(); p.addListener('stateChange', () => p.play); } 0");
  const std::weak_ptr<cpp::SharedObject> watcher = env.watcher->last;
  env.watcher->last.reset();

  CHECK(collectedAway(env, watcher));
}

TEST(event_a_subscription_held_by_its_own_listener_does_not_keep_the_emitter_alive) {
  Env env;
  env.exec(
    "{ const p = expo.modules.Watcher.create();"
    "  const subscription = p.addListener('stateChange', () => subscription.remove()); } 0"
  );
  const std::weak_ptr<cpp::SharedObject> watcher = env.watcher->last;
  env.watcher->last.reset();

  CHECK(collectedAway(env, watcher));
}

TEST(event_a_subscription_does_not_keep_its_emitter_alive_either) {
  Env env;
  env.exec("globalThis.subscription = expo.modules.Watcher.create().addListener('stateChange', () => {}); 0");
  const std::weak_ptr<cpp::SharedObject> watcher = env.watcher->last;
  env.watcher->last.reset();

  CHECK(collectedAway(env, watcher));
  // What it removes went with the emitter.
  env.exec("subscription.remove()");
}

TEST(event_a_subscription_removes_its_own_listener_once) {
  Env env;
  env.exec(
    "globalThis.p = expo.modules.Watcher.create();"
    "globalThis.f = () => seen.push('f');"
    "globalThis.first = p.addListener('stateChange', f);"
    "globalThis.second = p.addListener('stateChange', f);"
  );
  CHECK_EQ(env.eval("p.listenerCount('stateChange')"), "2");

  // Each subscription stands for one registration, however often it is asked to remove it.
  env.exec("first.remove(); first.remove(); first.remove();");
  CHECK_EQ(env.eval("p.listenerCount('stateChange')"), "1");
  CHECK_EQ(env.watcher->last->starts, 1);
  CHECK_EQ(env.watcher->last->stops, 0);

  env.exec("second.remove();");
  CHECK_EQ(env.eval("p.listenerCount('stateChange')"), "0");
  CHECK_EQ(env.watcher->last->stops, 1);
}

TEST(event_listeners_can_be_taken_off_a_released_object) {
  Env env;
  env.exec(
    "globalThis.p = expo.modules.Watcher.create();"
    "globalThis.f = () => {};"
    "globalThis.subscription = p.addListener('stateChange', f);"
    "p.release();"
  );

  // Cleanup that runs after the owner let go must not fail.
  CHECK_EQ(env.errorOf("subscription.remove()"), "no error");
  CHECK_EQ(env.errorOf("p.removeListener('stateChange', f)"), "no error");
  CHECK_EQ(env.errorOf("p.removeAllListeners('stateChange')"), "no error");
  CHECK_EQ(env.eval("p.listenerCount('stateChange')"), "0");
  CHECK_CONTAINS(env.errorOf("p.addListener('stateChange', f)"), "released");
  CHECK_CONTAINS(env.errorOf("p.emit('stateChange')"), "released");
}

TEST(event_the_listeners_are_not_visible_as_properties_of_the_emitter) {
  Env env;
  env.exec("globalThis.p = expo.modules.Watcher.create();");
  CHECK_EQ(env.eval("p.listenerCount('stateChange')"), "0");
  CHECK_EQ(env.errorOf("p.removeAllListeners('stateChange')"), "no error");
  // The store is not an own property anyone can list or enumerate.
  env.exec("p.addListener('stateChange', () => {});");
  CHECK_EQ(env.eval("Object.keys(p).length + ',' + JSON.stringify(p)"), "0,{}");
  CHECK_EQ(env.eval("Object.getOwnPropertyDescriptors(p)[Object.getOwnPropertySymbols(p)[0]].enumerable"), "false");
}

namespace {
  class Fragile : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override { def.function("noop", [] {}); }

    cpp::Event<int> onTick{
      this,
      "tick",
      {
        .onStartObserving = [] { throw std::runtime_error("cannot start"); },
        .onStopObserving = [] { throw std::runtime_error("cannot stop"); },
      },
    };
  };
} // namespace

TEST(event_a_hook_that_throws_is_logged_and_neither_loses_the_listener_nor_ends_the_process) {
  auto fragile = std::make_shared<Fragile>();
  {
    JsEnv env;
    env.context().add("Fragile", fragile);
    env.exec("globalThis.n = 0; const fn = () => { n++; }; globalThis.subscription = expo.modules.Fragile.addListener('tick', fn);");

    fragile->onTick.emit(1);
    env.pump();
    CHECK_EQ(env.eval("n"), "1");

    env.exec("subscription.remove();");
    env.exec("expo.modules.Fragile.addListener('tick', () => {}); expo.modules.Fragile.removeAllListeners('tick');");
    // Left in place for the runtime's teardown, which stops observing from a destructor.
    env.exec("expo.modules.Fragile.addListener('tick', () => {});");
  }
  CHECK(!fragile->onTick.isObserved());
}
