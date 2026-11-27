@file:JvmName("LeafConverters")

package io.github.expo.modules.v2.converters

import io.github.expo.modules.v2.types.CppType

fun boxedBooleanConverter(isNullable: Boolean): TypeConverter<*> = BoxedBooleanConverter(isNullable)

fun boxedIntConverter(isNullable: Boolean): TypeConverter<*> = BoxedIntConverter(isNullable)

fun boxedLongConverter(isNullable: Boolean): TypeConverter<*> = BoxedLongConverter(isNullable)

fun boxedFloatConverter(isNullable: Boolean): TypeConverter<*> = BoxedFloatConverter(isNullable)

fun boxedDoubleConverter(isNullable: Boolean): TypeConverter<*> = BoxedDoubleConverter(isNullable)

fun stringConverter(isNullable: Boolean): TypeConverter<*> = StringConverter(isNullable)

fun unitConverter(isNullable: Boolean): TypeConverter<*> = UnitConverter(isNullable)

fun dynamicConverter(isNullable: Boolean): TypeConverter<*> = DynamicConverter(isNullable)

fun jsValueConverter(isNullable: Boolean): TypeConverter<*> = JsHandleConverter(CppType.JS_VALUE, isNullable)

fun jsObjectConverter(isNullable: Boolean): TypeConverter<*> = JsHandleConverter(CppType.JS_OBJECT, isNullable)

fun durationConverter(isNullable: Boolean): TypeConverter<*> = DurationConverter(isNullable)

fun fileConverter(isNullable: Boolean): TypeConverter<*> = FileConverter(isNullable)

fun urlConverter(isNullable: Boolean): TypeConverter<*> = UrlConverter(isNullable)

fun uriConverter(isNullable: Boolean): TypeConverter<*> = UriConverter(isNullable)

fun kotlinUuidConverter(isNullable: Boolean): TypeConverter<*> = KotlinUuidConverter(isNullable)

fun uuidConverter(isNullable: Boolean): TypeConverter<*> = UuidConverter(isNullable)
