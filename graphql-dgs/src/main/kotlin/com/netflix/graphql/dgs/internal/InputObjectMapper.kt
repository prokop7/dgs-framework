/*
 * Copyright 2022 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.graphql.dgs.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.graphql.dgs.exceptions.DgsInvalidInputArgumentException
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.lang.reflect.Type
import java.util.Optional
import kotlin.reflect.KClass

/**
 * SPI intended for other frameworks/libraries that need to customize how input objects are mapped.
 * Not intended to be used by most users.
 *
 * A custom mapper might call the DefaultInputObjectMapper, passing itself to the DefaultInputObjectMapper constructor.
 * The DefaultInputObjectMapper will invoke the custom mapper each time it goes a level deeper into a nested object structure.
 * This makes it possible to have a custom mapper that still mostly relies on the default one.
 * Be careful to not create an infinite recursion calling back and forward though!
 *
 * Kotlin and Java objects are converted differently. This is to deal with things like data classes.
 * The input to the map methods is a map of values that are already converted by scalars.
 * The input IS NOT JSON. Attempting to use a JSON mapper to converting these values will result in incorrect scalar values.
 */
interface InputObjectMapper {
    /**
     * Convert a map of input values to a Kotlin object. This must support Kotlin constructs such as data classes, and is typically handled differently from Java.
     * @param inputMap The fields for an input object represented as a Map. This can be a nested map if nested types are used. Note that the values in this map are already converted by the scalars representing these types.
     * @param targetClass The class to convert to.
     * @return The converted object
     */
    fun <T : Any> mapToKotlinObject(inputMap: Map<String, *>, targetClass: KClass<T>): T

    /**
     * Convert a map of input values to a Java object.
     * @param inputMap The fields for an input object represented as a Map. This can be a nested map if nested types are used. Note that the values in this map are already converted by the scalars representing these types.
     * @param targetClass The class to convert to.
     * @return The converted object
     */
    fun <T> mapToJavaObject(inputMap: Map<String, *>, targetClass: Class<T>): T

    fun convert(input: Any?, targetType: Type): Any?
}

class DefaultInputObjectMapper(
    @Deprecated("customInputObjectMapper is not used, register an InputObjectMapper bean instead to customize")
    val customInputObjectMapper: InputObjectMapper? = null
) : InputObjectMapper {

    companion object {
        private val mapper: ObjectMapper = Jackson2ObjectMapperBuilder()
            .failOnUnknownProperties(false)
            .build()
    }

    override fun <T : Any> mapToKotlinObject(inputMap: Map<String, *>, targetClass: KClass<T>): T {
        return convert(inputMap, targetClass.java)
    }

    override fun <T> mapToJavaObject(inputMap: Map<String, *>, targetClass: Class<T>): T {
        return convert(inputMap, targetClass)
    }

    override fun convert(input: Any?, targetType: Type): Any? {
        var type = mapper.typeFactory.constructType(targetType)

        if (input != null && !type.hasGenericTypes() && type.isTypeOrSuperTypeOf(input::class.java)) {
            return input
        }

        var isOptional = false
        if (type.isTypeOrSubTypeOf(Optional::class.java)) {
            isOptional = true
            if (input == null) {
                return Optional.empty<Any>()
            }
            type = type.findTypeParameters(Optional::class.java)[0]
        }

        val value = try {
            mapper.convertValue<Any?>(input, type)
        } catch (exc: IllegalArgumentException) {
            throw DgsInvalidInputArgumentException(
                exc.message
                    ?: "Mapping failed due to incompatible types"
            )
        }
        return if (isOptional) {
            Optional.ofNullable(value)
        } else {
            value
        }
    }

    private fun <T> convert(input: Any, targetClass: Class<T>): T {
        return try {
            mapper.convertValue(input, targetClass)
        } catch (exc: IllegalArgumentException) {
            throw DgsInvalidInputArgumentException(
                exc.message
                    ?: "Mapping failed due to incompatible types"
            )
        }
    }
}
