/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.fir.FirModuleBasedSession
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSessionProvider
import org.jetbrains.kotlin.fir.resolve.FirProvider
import org.jetbrains.kotlin.fir.resolve.FirSymbolProvider
import org.jetbrains.kotlin.fir.resolve.impl.FirCompositeSymbolProvider
import org.jetbrains.kotlin.fir.resolve.impl.FirDependenciesSymbolProviderImpl
import org.jetbrains.kotlin.fir.resolve.impl.FirLibrarySymbolProviderImpl
import org.jetbrains.kotlin.fir.service

class FirJavaModuleBasedSession(
    moduleInfo: ModuleInfo,
    override val sessionProvider: FirProjectSessionProvider,
    module: Module? = null
) : FirModuleBasedSession(moduleInfo) {
    init {
        sessionProvider.sessionCache[moduleInfo] = this
        registerComponent(
            FirSymbolProvider::class,
            FirCompositeSymbolProvider(
                listOf(
                    service<FirProvider>(),
                    JavaSourceSymbolProvider(sessionProvider.project, module),
                    FirLibrarySymbolProviderImpl(this),
                    JavaLibrariesSymbolProvider(sessionProvider.project, module),
                    FirDependenciesSymbolProviderImpl(this)
                )
            )
        )
    }
}

class FirProjectSessionProvider(val project: Project) : FirSessionProvider {
    override fun getSession(moduleInfo: ModuleInfo): FirSession? {
        return sessionCache[moduleInfo]
    }

    val sessionCache = mutableMapOf<ModuleInfo, FirSession>()
}