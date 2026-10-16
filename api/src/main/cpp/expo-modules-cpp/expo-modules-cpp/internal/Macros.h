#pragma once

// The preprocessor machinery behind `EXPO_ENUM` and `EXPO_RECORD`: a list of elements, each of
// which is either a bare name or a parenthesized `(name, argument)`, mapped to a comma-separated
// list of expressions.

#define EXPO_DETAIL_NARG_(_1, _2, _3, _4, _5, _6, _7, _8, _9, _10, _11, _12, _13, _14, _15, _16, _17, _18, _19, _20, _21, _22, _23, _24, _25, _26, _27, _28, _29, _30, _31, _32, N, ...) N
#define EXPO_DETAIL_NARG(...) EXPO_DETAIL_NARG_(__VA_ARGS__, 32, 31, 30, 29, 28, 27, 26, 25, 24, 23, 22, 21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1)
#define EXPO_DETAIL_CAT_(a, b) a##b
#define EXPO_DETAIL_CAT(a, b) EXPO_DETAIL_CAT_(a, b)

// Applies `macro(context, element)` to each element, separated by commas. Up to 32 elements.
#define EXPO_DETAIL_FE_1(m, c, x) m(c, x)
#define EXPO_DETAIL_FE_2(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_1(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_3(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_2(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_4(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_3(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_5(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_4(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_6(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_5(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_7(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_6(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_8(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_7(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_9(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_8(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_10(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_9(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_11(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_10(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_12(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_11(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_13(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_12(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_14(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_13(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_15(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_14(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_16(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_15(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_17(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_16(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_18(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_17(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_19(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_18(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_20(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_19(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_21(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_20(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_22(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_21(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_23(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_22(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_24(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_23(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_25(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_24(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_26(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_25(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_27(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_26(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_28(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_27(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_29(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_28(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_30(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_29(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_31(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_30(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FE_32(m, c, x, ...) m(c, x), EXPO_DETAIL_FE_31(m, c, __VA_ARGS__)
#define EXPO_DETAIL_FOR_EACH(m, c, ...) EXPO_DETAIL_CAT(EXPO_DETAIL_FE_, EXPO_DETAIL_NARG(__VA_ARGS__))(m, c, __VA_ARGS__)

// Whether an element is parenthesized: 1 for `(a, b)`, 0 for `a`.
//
// `PAREN_PROBE x` expands to `~, 1` only when `x` is parenthesized. CHECK is variadic so that the
// probe is expanded before SECOND splits its arguments at the comma the probe produced.
#define EXPO_DETAIL_SECOND(a, b, ...) b
#define EXPO_DETAIL_CHECK(...) EXPO_DETAIL_SECOND(__VA_ARGS__, 0, ~)
#define EXPO_DETAIL_PAREN_PROBE(...) ~, 1
#define EXPO_DETAIL_IS_PAREN(x) EXPO_DETAIL_CHECK(EXPO_DETAIL_PAREN_PROBE x)
#define EXPO_DETAIL_IF_0(then, otherwise) otherwise
#define EXPO_DETAIL_IF_1(then, otherwise) then
#define EXPO_DETAIL_IF(condition) EXPO_DETAIL_CAT(EXPO_DETAIL_IF_, condition)

// `EXPO_DETAIL_CALL(macro, (a, b))` is `macro(a, b)`: the arguments are split only after the
// parentheses were expanded, which a direct call would not do.
#define EXPO_DETAIL_UNPAREN(...) __VA_ARGS__
#define EXPO_DETAIL_CALL(macro, arguments) macro arguments
