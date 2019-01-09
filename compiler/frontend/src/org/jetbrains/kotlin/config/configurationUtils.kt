/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.config

fun <T> CompilerConfiguration.putIf(key: CompilerConfigurationKey<T>, value: T, condition: (T) -> Boolean) {
    if (condition(value)) {
        put(key, value)
    }
}

fun CompilerConfiguration.putIfTrue(key: CompilerConfigurationKey<Boolean>, value: Boolean) {
    putIf(key, value) { it }
}

fun <T : Any> CompilerConfiguration.putIfNotNull(key: CompilerConfigurationKey<T>, value: T?) {
    if (value != null) {
        put(key, value)
    }
}

@JvmName("putSetIfNotNull")
fun <T : Any> CompilerConfiguration.putIfNotNull(key: CompilerConfigurationKey<Set<T>>, values: Array<T>?) {
    if (values != null) {
        put(key, setOf(*values))
    }
}

@JvmName("putListIfNotNull")
fun <T : Any> CompilerConfiguration.putIfNotNull(key: CompilerConfigurationKey<List<T>>, values: Array<T>?) {
    if (values != null) {
        put(key, listOf(*values))
    }
}

fun <T : Any> CompilerConfiguration.addAllIfNotNull(key: CompilerConfigurationKey<List<T>>, values: Array<T>?) {
    if (values != null) {
        addAll(key, getList(key).size, values.toList())
    }
}
