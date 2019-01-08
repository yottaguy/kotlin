/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.common

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.metadata.deserialization.BinaryVersion

fun <A : CommonCompilerArguments> CompilerConfiguration.setupMessageCollector(
    messageCollector: MessageCollector,
    arguments: A
): GroupingMessageCollector =
    GroupingMessageCollector(messageCollector, arguments.allWarningsAsErrors).also {
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