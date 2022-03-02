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

package com.netflix.graphql.dgs.autoconfig.testcomponents

import com.fasterxml.jackson.annotation.JsonIgnore
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
open class CustomInputObjectMapperConfig {
    @Bean
    open fun dataFetcher(): DataFetcherWithInputObject {
        return DataFetcherWithInputObject()
    }
}

@DgsComponent
class DataFetcherWithInputObject {
    @DgsQuery
    fun withIgnoredField(@InputArgument input: Input): Input {
        return input
    }

    @DgsQuery
    fun withIgnoredFieldNested(@InputArgument nestedInput: NestedInput): Input {
        return nestedInput.input
    }

    data class Input(@JsonIgnore val ignoredField: String?, val name: String)
    data class NestedInput(val input: Input)
}
