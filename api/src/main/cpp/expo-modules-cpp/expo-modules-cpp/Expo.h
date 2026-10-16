#pragma once

// The Expo Modules API v2 for C++: write a native module once, as plain C++, and call it from
// JavaScript. Including this header gives you everything; see README.md for a guide.
//
//   #include <expo-modules-cpp/Expo.h>
//
//   class MathModule : public cpp::Module {
//   public:
//     void define(cpp::ModuleDefinition& def) override {
//       def.function("add", [](double a, double b) { return a + b; });
//     }
//   };
//
//   EXPO_MODULE(MathModule)
//
// Or mark each member where it is declared, like Kotlin's @JS (see Annotations.h):
//
//   class MathModule : public cpp::ExpoModule<MathModule> {
//   public:
//     EXPO_JS(add)
//     int add(int a, int b) { return a + b; }
//   };
//
//   EXPO_MODULE(MathModule)

#include <expo-modules-cpp/Annotations.h>
#include <expo-modules-cpp/Any.h>
#include <expo-modules-cpp/AttachedRuntime.h>
#include <expo-modules-cpp/Context.h>
#include <expo-modules-cpp/Converter.h>
#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/Definition.h>
#include <expo-modules-cpp/Enum.h>
#include <expo-modules-cpp/Errors.h>
#include <expo-modules-cpp/Event.h>
#include <expo-modules-cpp/Module.h>
#include <expo-modules-cpp/Promise.h>
#include <expo-modules-cpp/Record.h>
#include <expo-modules-cpp/Scheduler.h>
#include <expo-modules-cpp/SharedObject.h>
#include <expo-modules-cpp/Task.h>
#include <expo-modules-cpp/TypedArray.h>
