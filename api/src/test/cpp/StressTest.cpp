#include "Fixture.h"

#include <atomic>
#include <future>
#include <thread>

namespace {
  class Busy : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("hold", [this](cpp::Promise<int> promise) {
        const std::lock_guard lock(mutex);
        held.push_back(promise);
      });
      def.function("heldCount", [this] {
        const std::lock_guard lock(mutex);
        return static_cast<int>(held.size());
      });
    }

    std::mutex mutex;
    std::vector<cpp::Promise<int>> held;
    cpp::Event<int> onTick{this, "tick"};
  };

  class Light : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override { def.function("one", [] { return 1; }); }
  };
} // namespace

EXPO_MODULE(Light)

TEST(stress_many_threads_emitting_deliver_every_event_to_the_js_thread) {
  JsEnv env;
  auto busy = env.context().emplace<Busy>("Busy");
  env.exec("globalThis.total = 0; globalThis.count = 0; expo.modules.Busy.addListener('tick', (n) => { total += n; count++; });");

  constexpr int kThreads = 8;
  constexpr int kEmits = 500;
  std::vector<std::thread> threads;
  for (int t = 0; t < kThreads; t++) {
    threads.emplace_back([&] {
      for (int i = 0; i < kEmits; i++) {
        busy->onTick.emit(1);
      }
    });
  }
  for (std::thread& thread: threads) {
    thread.join();
  }

  env.pumpUntil("count === " + std::to_string(kThreads * kEmits));
  CHECK_EQ(env.eval("total"), std::to_string(kThreads * kEmits));
}

TEST(stress_listeners_coming_and_going_while_other_threads_emit) {
  JsEnv env;
  auto busy = env.context().emplace<Busy>("Busy");

  std::atomic<bool> stop{false};
  std::thread emitter([&] {
    while (!stop) {
      busy->onTick.emit(1);
    }
  });

  for (int i = 0; i < 200; i++) {
    env.exec("const s = expo.modules.Busy.addListener('tick', () => {}); s.remove();");
    env.pump();
  }
  stop = true;
  emitter.join();
  env.pump();
  CHECK(!busy->onTick.isObserved());
}

TEST(stress_many_threads_settling_many_promises) {
  JsEnv env;
  auto busy = env.context().emplace<Busy>("Busy");
  constexpr int kPromises = 400;
  env.exec(
    "globalThis.sum = 0; globalThis.settled = 0;"
    "for (let i = 0; i < " + std::to_string(kPromises) + "; i++) { expo.modules.Busy.hold().then((v) => { sum += v; settled++; }); }"
  );
  CHECK_EQ(env.eval("expo.modules.Busy.heldCount()"), std::to_string(kPromises));

  std::vector<std::thread> threads;
  for (int t = 0; t < 4; t++) {
    threads.emplace_back([&, t] {
      for (int i = t; i < kPromises; i += 4) {
        busy->held[static_cast<size_t>(i)].resolve(i);
        // A second settle from another thread must be ignored.
        busy->held[static_cast<size_t>(i)].resolve(-1);
      }
    });
  }
  for (std::thread& thread: threads) {
    thread.join();
  }

  env.pumpUntil("settled === " + std::to_string(kPromises));
  CHECK_EQ(env.eval("sum"), std::to_string(kPromises * (kPromises - 1) / 2));
}

TEST(stress_contexts_look_modules_up_from_many_threads) {
  auto context = cpp::Context::create();
  std::vector<std::thread> threads;
  std::atomic<int> found{0};
  for (int t = 0; t < 8; t++) {
    threads.emplace_back([&] {
      for (int i = 0; i < 200; i++) {
        if (context->module<Light>() != nullptr && context->module("Light") != nullptr) {
          found++;
        }
      }
    });
  }
  for (std::thread& thread: threads) {
    thread.join();
  }
  CHECK_EQ(found.load(), 8 * 200);
  // One instance, however many threads asked at once.
  CHECK(context->module<Light>() == context->module("Light"));
}

TEST(stress_runtimes_come_and_go_while_a_thread_keeps_emitting) {
  auto context = cpp::Context::create(false);
  auto busy = context->emplace<Busy>("Busy");
  std::atomic<bool> stop{false};
  std::thread emitter([&] {
    while (!stop) {
      busy->onTick.emit(1);
    }
  });

  for (int i = 0; i < 30; i++) {
    JsEnv env(context);
    env.exec("expo.modules.Busy.addListener('tick', () => {});");
    env.pump();
  }
  stop = true;
  emitter.join();
  CHECK(!busy->onTick.isObserved());
}

TEST(stress_asking_for_the_js_thread_while_the_runtime_is_detached_and_attached_again) {
  // `runOnJSThread` and `isOnJSThread` are for other threads, so they race the AttachedRuntime that
  // is being destroyed on the JS thread; what they reach through the registry has to outlive the
  // lookup.
  auto runtime = facebook::hermes::makeHermesRuntime();
  auto context = cpp::Context::create(false);
  auto scheduler = std::make_shared<cpp::ManualScheduler>();

  std::atomic<bool> stop{false};
  std::atomic<int> asked{0};
  std::thread asker([&] {
    while (!stop) {
      (void) cpp::isOnJSThread(*runtime);
      cpp::runOnJSThread(*runtime, [](js::Runtime&) {});
      asked++;
    }
  });

  for (int i = 0; i < 300; i++) {
    auto attached = cpp::AttachedRuntime::attach(*runtime, context, scheduler, {.installGlobal = false});
    attached.reset();
    scheduler->drain();
  }
  stop = true;
  asker.join();
  scheduler->close();
  context->close();
  CHECK(asked.load() > 0);
}

namespace {
  void spinFor(const std::chrono::nanoseconds duration) {
    const auto end = std::chrono::steady_clock::now() + duration;
    while (std::chrono::steady_clock::now() < end) {
    }
  }
} // namespace

TEST(stress_a_drain_returns_even_when_other_threads_post_faster_than_jobs_run) {
  // A job takes 10 us, as a job that calls a JavaScript listener does, and a job is posted every
  // 2 us: the queue grows five jobs for every one that runs. A drain that ran until the queue was
  // empty would never return, and the thread that called it would never get to run JavaScript.
  cpp::ManualScheduler scheduler;
  std::atomic<bool> stop{false};
  std::atomic<int> posted{0};
  std::atomic<int> ran{0};
  std::thread producer([&] {
    while (!stop) {
      scheduler.post([&] {
        ran++;
        spinFor(std::chrono::microseconds(10));
      });
      posted++;
      spinFor(std::chrono::microseconds(2));
    }
  });

  while (posted.load() < 200) {
    std::this_thread::yield();
  }

  // Each drain takes the jobs there are when it starts, which are five times as many as the one
  // before, so a few are enough to show that every one of them comes back.
  size_t drained = 0;
  for (int drain = 0; drain < 4; drain++) {
    drained += scheduler.drain();
  }
  stop = true;
  producer.join();
  scheduler.close();

  CHECK(drained >= 200);
  CHECK(ran.load() > 0);
}

TEST(stress_closing_a_scheduler_destroys_the_queued_jobs_outside_its_lock) {
  // What a job holds may post to the scheduler when it dies - a coroutine frame being destroyed
  // does - so the jobs must not die with the lock held. Watched, so that a regression fails the
  // test instead of hanging the run.
  struct PostsWhenDestroyed {
    cpp::ManualScheduler* scheduler;

    ~PostsWhenDestroyed() { scheduler->post([] {}); }
  };

  cpp::ManualScheduler scheduler;
  scheduler.post([guard = std::make_shared<PostsWhenDestroyed>(&scheduler)] {});

  std::promise<void> closed;
  std::thread closer([&] {
    scheduler.close();
    closed.set_value();
  });
  const bool finished = closed.get_future().wait_for(std::chrono::seconds(5)) == std::future_status::ready;
  if (finished) {
    closer.join();
  } else {
    closer.detach();
  }
  CHECK(finished);
}
