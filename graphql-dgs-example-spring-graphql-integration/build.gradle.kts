/*
 * Copyright 2021 Netflix, Inc.
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

plugins {
    id("org.springframework.boot") version "2.6.1"
}

dependencies {
    implementation(project(":graphql-dgs"))
    implementation(project(":graphql-dgs-reactive"))
    implementation(project(":graphql-dgs-example-shared"))
    implementation(project(":spring-graphql-bridge"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.graphql:spring-graphql:1.0.0-M3")
    implementation("com.querydsl:querydsl-core:5.0.0")
    implementation("com.querydsl:querydsl-collections:5.0.0")

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.data:spring-data-commons")
    implementation("org.springframework.data:spring-data-keyvalue")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.graphql:spring-graphql-test:1.0.0-M3")

    kapt("com.querydsl:querydsl-apt:5.0.0:general")
}
