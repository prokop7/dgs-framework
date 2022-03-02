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

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext
import com.netflix.graphql.dgs.exceptions.DgsInvalidInputArgumentException
import com.netflix.graphql.dgs.exceptions.DgsMissingCookieException
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asCompletableFuture
import org.slf4j.LoggerFactory
import org.springframework.core.DefaultParameterNameDiscoverer
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.util.ReflectionUtils
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ValueConstants
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.kotlinFunction

class DataFetcherInvoker(
    private val cookieValueResolver: Optional<CookieValueResolver>,
    defaultParameterNameDiscoverer: DefaultParameterNameDiscoverer,
    private val environment: DataFetchingEnvironment,
    private val dgsComponent: Any,
    private val method: Method,
    private val inputObjectMapper: InputObjectMapper,
) {

    private val parameterNames = defaultParameterNameDiscoverer.getParameterNames(method) ?: emptyArray()

    fun invokeDataFetcher(): Any? {
        val args = mutableListOf<Any?>()
        method.parameters.asSequence().filter { it.type != Continuation::class.java }.forEachIndexed { idx, parameter ->

            when {
                parameter.isAnnotationPresent(InputArgument::class.java) -> args.add(
                    processInputArgument(
                        parameter,
                        idx
                    )
                )
                parameter.isAnnotationPresent(RequestHeader::class.java) -> args.add(
                    processRequestHeader(
                        environment,
                        parameter,
                        idx
                    )
                )
                parameter.isAnnotationPresent(RequestParam::class.java) -> args.add(
                    processRequestArgument(
                        environment,
                        parameter,
                        idx
                    )
                )
                parameter.isAnnotationPresent(CookieValue::class.java) -> args.add(
                    processCookieValueArgument(
                        environment,
                        parameter,
                        idx
                    )
                )

                environment.containsArgument(parameterNames[idx]) -> {
                    val parameterValue: Any = environment.getArgument(parameterNames[idx])
                    val convertValue = inputObjectMapper.convert(parameterValue, parameter.parameterizedType)
                    args.add(convertValue)
                }

                parameter.type == DataFetchingEnvironment::class.java || parameter.type == DgsDataFetchingEnvironment::class.java -> {
                    args.add(environment)
                }
                else -> {
                    logger.debug("Unknown argument '${parameterNames[idx]}' on data fetcher ${dgsComponent.javaClass.name}.${method.name}")
                    // This might cause an exception, but parameter's the best effort we can do
                    args.add(null)
                }
            }
        }

        return if (method.kotlinFunction?.isSuspend == true) {

            val launch = CoroutineScope(Dispatchers.Unconfined).async {
                try {
                    method.kotlinFunction!!.callSuspend(dgsComponent, *args.toTypedArray())
                } catch (exception: InvocationTargetException) {
                    throw exception.cause ?: exception
                }
            }

            launch.asCompletableFuture()
        } else {
            ReflectionUtils.makeAccessible(method)
            ReflectionUtils.invokeMethod(method, dgsComponent, *args.toTypedArray())
        }
    }

    private fun processCookieValueArgument(environment: DataFetchingEnvironment, parameter: Parameter, idx: Int): Any? {
        val requestData = DgsContext.getRequestData(environment)
        val annotation = AnnotationUtils.getAnnotation(parameter, CookieValue::class.java)!!
        val name: String = AnnotationUtils.getAnnotationAttributes(annotation)["name"] as String
        val parameterName = name.ifBlank { parameterNames[idx] }
        val value = if (cookieValueResolver.isPresent) {
            cookieValueResolver.get().getCookieValue(parameterName, requestData)
        } else {
            null
        }
            ?: if (annotation.defaultValue != ValueConstants.DEFAULT_NONE) annotation.defaultValue else null

        if (value == null && annotation.required) {
            throw DgsMissingCookieException(parameterName)
        }

        return getValueAsOptional(value, parameter)
    }

    private fun processRequestArgument(environment: DataFetchingEnvironment, parameter: Parameter, idx: Int): Any? {
        val requestData = DgsContext.getRequestData(environment)
        val annotation = AnnotationUtils.getAnnotation(parameter, RequestParam::class.java)!!
        val name: String = AnnotationUtils.getAnnotationAttributes(annotation)["name"] as String
        val parameterName = name.ifBlank { parameterNames[idx] }
        if (requestData is DgsWebMvcRequestData) {
            val webRequest = requestData.webRequest
            val value: Any? =
                webRequest?.parameterMap?.get(parameterName)?.let {
                    if (parameter.type.isAssignableFrom(List::class.java)) {
                        it
                    } else {
                        it.joinToString()
                    }
                }
                    ?: if (annotation.defaultValue != ValueConstants.DEFAULT_NONE) annotation.defaultValue else null

            if (value == null && annotation.required) {
                throw DgsInvalidInputArgumentException("Required request parameter '$parameterName' was not provided")
            }

            return getValueAsOptional(value, parameter)
        } else {
            logger.warn("@RequestParam is not supported when using WebFlux")
            return null
        }
    }

    private fun processRequestHeader(environment: DataFetchingEnvironment, parameter: Parameter, idx: Int): Any? {
        val requestData = DgsContext.getRequestData(environment)
        val annotation = AnnotationUtils.getAnnotation(parameter, RequestHeader::class.java)!!
        val name: String = AnnotationUtils.getAnnotationAttributes(annotation)["name"] as String
        val parameterName = name.ifBlank { parameterNames[idx] }
        val value = requestData?.headers?.get(parameterName)?.let {
            if (parameter.type.isAssignableFrom(List::class.java)) {
                it
            } else {
                it.joinToString()
            }
        } ?: if (annotation.defaultValue != ValueConstants.DEFAULT_NONE) annotation.defaultValue else null

        if (value == null && annotation.required) {
            throw DgsInvalidInputArgumentException("Required header '$parameterName' was not provided")
        }

        return getValueAsOptional(value, parameter)
    }

    private fun processInputArgument(parameter: Parameter, parameterIndex: Int): Any? {
        val annotation = AnnotationUtils.getAnnotation(parameter, InputArgument::class.java)!!
        val name: String = AnnotationUtils.getAnnotationAttributes(annotation)["name"] as String

        val parameterName = name.ifBlank { parameterNames[parameterIndex] }
        val parameterValue = environment.getArgument<Any?>(parameterName)

        return inputObjectMapper.convert(parameterValue, parameter.parameterizedType)
    }

    private fun getValueAsOptional(value: Any?, parameter: Parameter) =
        if (parameter.type.isAssignableFrom(Optional::class.java)) {
            Optional.ofNullable(value)
        } else {
            value
        }

    companion object {
        private val logger = LoggerFactory.getLogger(DataFetcherInvoker::class.java)
    }
}
