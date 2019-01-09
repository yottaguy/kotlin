/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.common

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments
import org.jetbrains.kotlin.cli.common.arguments.ManualLanguageFeatureSetting
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.metadata.deserialization.BinaryVersion
import org.jetbrains.kotlin.utils.KotlinPaths
import org.jetbrains.kotlin.utils.KotlinPathsFromHomeDir
import org.jetbrains.kotlin.utils.PathUtil
import java.io.File

fun <A : CommonCompilerArguments> CompilerConfiguration.setupMessageCollector(
    baseMessageCollector: MessageCollector,
    arguments: A
): GroupingMessageCollector =
    GroupingMessageCollector(baseMessageCollector, arguments.allWarningsAsErrors).also {
        put(MESSAGE_COLLECTOR_KEY, it)
    }

fun <A : CommonCompilerArguments> CompilerConfiguration.setupCommonArguments(
    arguments: A,
    createMetadataVersion: ((IntArray) -> BinaryVersion)? = null
) {
    if (arguments.noInline) {
        put(CommonConfigurationKeys.DISABLE_INLINE, true)
    }
    if (arguments.intellijPluginRoot != null) {
        put(CLIConfigurationKeys.INTELLIJ_PLUGIN_ROOT, arguments.intellijPluginRoot!!)
    }
    if (arguments.reportOutputFiles) {
        put(CommonConfigurationKeys.REPORT_OUTPUT_FILES, true)
    }

    val metadataVersionString = arguments.metadataVersion
    if (metadataVersionString != null) {
        val versionArray = BinaryVersion.parseVersionArray(metadataVersionString)
        val messageCollector = getNotNull(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY)
        if (versionArray == null) {
            messageCollector.report(CompilerMessageSeverity.ERROR, "Invalid metadata version: $metadataVersionString", null)
        } else if (createMetadataVersion == null) {
            messageCollector.report(CompilerMessageSeverity.ERROR, "Unable to create metadata version: missing argument", null)
        } else {
            put(CommonConfigurationKeys.METADATA_VERSION, createMetadataVersion(versionArray))
        }
    }

    this.setupLanguageVersionSettings(arguments)

    put(CommonConfigurationKeys.LIST_PHASES, arguments.listPhases)
    if (arguments.disablePhases != null) {
        put(CommonConfigurationKeys.DISABLED_PHASES, setOf(*arguments.disablePhases!!))
    }
    if (arguments.verbosePhases != null) {
        put(CommonConfigurationKeys.VERBOSE_PHASES, setOf(*arguments.verbosePhases!!))
    }
    if (arguments.phasesToDumpBefore != null) {
        put(
            CommonConfigurationKeys.PHASES_TO_DUMP_STATE_BEFORE,
            setOf(*arguments.phasesToDumpBefore!!)
        )
    }
    if (arguments.phasesToDumpAfter != null) {
        put(
            CommonConfigurationKeys.PHASES_TO_DUMP_STATE_AFTER,
            setOf(*arguments.phasesToDumpAfter!!)
        )
    }
    if (arguments.phasesToDump != null) {
        put(CommonConfigurationKeys.PHASES_TO_DUMP_STATE, setOf(*arguments.phasesToDump!!))
    }
    put(CommonConfigurationKeys.PROFILE_PHASES, arguments.profilePhases)
}

fun <A : CommonCompilerArguments> CompilerConfiguration.setupLanguageVersionSettings(arguments: A) {
    languageVersionSettings = arguments.configureLanguageVersionSettings(getNotNull(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY))
}

const val KOTLIN_HOME_PROPERTY = "kotlin.home"

fun computeKotlinPaths(messageCollector: MessageCollector, arguments: CommonCompilerArguments): KotlinPaths? {
    val kotlinHomeProperty = System.getProperty(KOTLIN_HOME_PROPERTY)
    val kotlinHome = when {
        arguments.kotlinHome != null -> File(arguments.kotlinHome!!)
        kotlinHomeProperty != null -> File(kotlinHomeProperty)
        else -> null
    }

    return when {
        kotlinHome == null -> PathUtil.kotlinPathsForCompiler
        kotlinHome.isDirectory -> KotlinPathsFromHomeDir(kotlinHome)
        else -> {
            messageCollector.report(CompilerMessageSeverity.ERROR, "Kotlin home does not exist or is not a directory: $kotlinHome", null)
            null
        }
    }?.also {
        messageCollector.report(CompilerMessageSeverity.LOGGING, "Using Kotlin home directory " + it.homePath, null)
    }
}

fun <A : CommonToolArguments> MessageCollector.reportArgumentParseProblems(arguments: A) {
    val errors = arguments.errors
    for (flag in errors.unknownExtraFlags) {
        report(CompilerMessageSeverity.STRONG_WARNING, "Flag is not supported by this version of the compiler: $flag")
    }
    for (argument in errors.extraArgumentsPassedInObsoleteForm) {
        report(
            CompilerMessageSeverity.STRONG_WARNING, "Advanced option value is passed in an obsolete form. Please use the '=' character " +
                    "to specify the value: $argument=..."
        )
    }
    for ((key, value) in errors.duplicateArguments) {
        report(CompilerMessageSeverity.STRONG_WARNING, "Argument $key is passed multiple times. Only the last value will be used: $value")
    }
    for ((deprecatedName, newName) in errors.deprecatedArguments) {
        report(CompilerMessageSeverity.STRONG_WARNING, "Argument $deprecatedName is deprecated. Please use $newName instead")
    }

    for (argfileError in errors.argfileErrors) {
        report(CompilerMessageSeverity.STRONG_WARNING, argfileError)
    }

    this.reportUnsafeInternalArgumentsIfAny(arguments)
    for (internalArgumentsError in errors.internalArgumentsParsingProblems) {
        report(CompilerMessageSeverity.STRONG_WARNING, internalArgumentsError)
    }
}

private fun <A : CommonToolArguments> MessageCollector.reportUnsafeInternalArgumentsIfAny(arguments: A) {
    val unsafeArguments = arguments.internalArguments.filterNot {
        // -XXLanguage which turns on BUG_FIX considered safe
        it is ManualLanguageFeatureSetting && it.languageFeature.kind == LanguageFeature.Kind.BUG_FIX && it.state == LanguageFeature.State.ENABLED
    }

    if (unsafeArguments.isNotEmpty()) {
        val unsafeArgumentsString = unsafeArguments.joinToString(prefix = "\n", postfix = "\n\n", separator = "\n") {
            it.stringRepresentation
        }

        report(
            CompilerMessageSeverity.STRONG_WARNING,
            "ATTENTION!\n" +
                    "This build uses unsafe internal compiler arguments:\n" +
                    unsafeArgumentsString +
                    "This mode is not recommended for production use,\n" +
                    "as no stability/compatibility guarantees are given on\n" +
                    "compiler or generated code. Use it at your own risk!\n"
        )
    }
}