/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir

import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.fir.FirRenderer
import org.jetbrains.kotlin.fir.builder.RawFirBuilder
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.java.FirJavaModuleBasedSession
import org.jetbrains.kotlin.fir.java.FirProjectSessionProvider
import org.jetbrains.kotlin.fir.resolve.FirProvider
import org.jetbrains.kotlin.fir.resolve.impl.FirProviderImpl
import org.jetbrains.kotlin.fir.resolve.transformers.FirTotalResolveTransformer
import org.jetbrains.kotlin.fir.service
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.caches.project.productionSourceInfo
import org.jetbrains.kotlin.idea.multiplatform.setupMppProjectFromDirStructure
import org.jetbrains.kotlin.idea.stubs.AbstractMultiModuleTest
import org.jetbrains.kotlin.idea.test.PluginTestCaseBase
import org.jetbrains.kotlin.idea.util.projectStructure.allModules
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.KotlinTestUtils
import java.io.File

abstract class AbstractFirMultiModuleResolveTest : AbstractMultiModuleTest() {
    override fun getTestDataPath(): String {
        return File(PluginTestCaseBase.getTestDataPathBase(), "/fir/multiModule").path + File.separator
    }

    fun doTest(dirPath: String) {
        setupMppProjectFromDirStructure(File(dirPath))
        doFirResolveTest(dirPath)
    }

    private fun createSession(module: Module, provider: FirProjectSessionProvider) =
        FirJavaModuleBasedSession(module.productionSourceInfo()!!, provider, module)

    private fun doFirResolveTest(dirPath: String) {
        val firFiles = mutableListOf<FirFile>()
        val provider = FirProjectSessionProvider(project)
        for (module in project.allModules().drop(1)) {
            val session = createSession(module, provider)

            val builder = RawFirBuilder(session)
            val psiManager = PsiManager.getInstance(project)

            val files = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, GlobalSearchScope.moduleScope(module))

            println("Got vfiles: ${files.size}")
            files.forEach {
                val file = psiManager.findFile(it) as? KtFile ?: return@forEach
                val firFile = builder.buildFirFile(file)
                (session.service<FirProvider>() as FirProviderImpl).recordFile(firFile)
                firFiles += firFile
            }
        }
        println("Raw fir up, files: ${firFiles.size}")

        fun expectedTxtPath(virtualFile: VirtualFile): String {
            val virtualPath = virtualFile.path
            var result: String? = null
            val root = File(dirPath)
            for (file in root.walkTopDown()) {
                if (!file.isDirectory && file.name in virtualPath) {
                    result = file.absolutePath.replace(".kt", ".txt")
                }
            }
            return result!!
        }

        val transformer = FirTotalResolveTransformer()
        for (file in firFiles) {
            transformer.processFile(file)
            val firFileDump = StringBuilder().also { file.accept(FirRenderer(it), null) }.toString()
            val expectedPath = expectedTxtPath((file.psi as PsiFile).virtualFile)
            KotlinTestUtils.assertEqualsToFile(File(expectedPath), firFileDump)
        }
    }
}