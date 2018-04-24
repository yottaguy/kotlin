/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.actions.internal

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.builder.RawFirBuilder
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.java.FirJavaModuleBasedSession
import org.jetbrains.kotlin.fir.java.FirProjectSessionProvider
import org.jetbrains.kotlin.fir.java.JavaSymbolProvider
import org.jetbrains.kotlin.fir.resolve.AbstractFirSymbolProvider
import org.jetbrains.kotlin.fir.resolve.FirProvider
import org.jetbrains.kotlin.fir.resolve.impl.FirProviderImpl
import org.jetbrains.kotlin.fir.resolve.transformers.FirTotalResolveTransformer
import org.jetbrains.kotlin.fir.service
import org.jetbrains.kotlin.fir.symbols.ConeSymbol
import org.jetbrains.kotlin.fir.types.ConeClassErrorType
import org.jetbrains.kotlin.fir.types.ConeKotlinErrorType
import org.jetbrains.kotlin.fir.types.FirResolvedType
import org.jetbrains.kotlin.fir.types.FirType
import org.jetbrains.kotlin.fir.visitors.FirTransformer
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.caches.project.*
import org.jetbrains.kotlin.idea.stubindex.KotlinFullClassNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinTopLevelTypeAliasFqNameIndex
import org.jetbrains.kotlin.idea.stubindex.PackageIndexUtil
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.idea.util.projectStructure.allModules
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.utils.addToStdlib.cast
import kotlin.reflect.KClass
import kotlin.system.measureNanoTime


class IdeFirDependenciesSymbolProvider(
    val moduleInfo: IdeaModuleInfo,
    val project: Project,
    private val sessionProvider: FirProjectSessionProvider
) : AbstractFirSymbolProvider() {


    // TODO: Our special scope here?
    private val depScope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope((moduleInfo as ModuleSourceInfo).module)

    private val javaSymbolProvider = JavaSymbolProvider(project, depScope)


    private fun buildKotlinClassOnRequest(file: KtFile, classId: ClassId, session: FirSession): ConeSymbol? {
        val impl = FirProvider.getInstance(session) as FirProviderImpl
        val classifier = impl.getSymbolByFqName(classId)
        if (classifier != null) {
            return classifier
        }

        val builder = RawFirBuilder(session)
        impl.recordFile(builder.buildFirFile(file))
        return impl.getSymbolByFqName(classId)
    }

    private fun selectNearest(classesPsi: Collection<KtDeclaration>, typeAliasesPsi: Collection<KtTypeAlias>): KtDeclaration? {
        return when {
            typeAliasesPsi.isEmpty() -> classesPsi.firstOrNull()
            classesPsi.isEmpty() -> typeAliasesPsi.firstOrNull()
            else -> (classesPsi.asSequence() + typeAliasesPsi.asSequence()).minWith(Comparator { o1, o2 ->
                depScope.compare(o1.containingFile.virtualFile, o2.containingFile.virtualFile)
            })
        }
    }

    private fun tryKotlin(classId: ClassId): ConeSymbol? {
        return classCache.lookupCacheOrCalculate(classId) {
            val index = KotlinFullClassNameIndex.getInstance()

            val fqNameString = classId.packageFqName.asString() + "." + classId.relativeClassName.asString()
            val classesPsi = index[fqNameString, project, depScope]
            val typeAliasesPsi = KotlinTopLevelTypeAliasFqNameIndex.getInstance()[fqNameString, project, depScope]

            val psi = selectNearest(classesPsi, typeAliasesPsi) ?: return@lookupCacheOrCalculate null

            val module = psi.getModuleInfo()
            if (psi.containingKtFile.isCompiled) {
                // TODO: WTF? Resolving libraries in current session
                val session = sessionProvider.getSession(moduleInfo) ?: return@lookupCacheOrCalculate null
                return@lookupCacheOrCalculate buildKotlinClassOnRequest(psi.containingKtFile, classId, session)
            }

            val session = sessionProvider.getSession(module) ?: return@lookupCacheOrCalculate null
            session.service<FirProvider>().getSymbolByFqName(classId)
        }
    }

    private fun tryJava(classId: ClassId): ConeSymbol? {
        return javaSymbolProvider.getSymbolByFqName(classId)
    }

    override fun getSymbolByFqName(classId: ClassId): ConeSymbol? {
        return tryKotlin(classId) ?: tryJava(classId)
    }

    override fun getPackage(fqName: FqName): FqName? {

        if (PackageIndexUtil.packageExists(fqName, depScope, project)) {
            return fqName
        }
        return javaSymbolProvider.getPackage(fqName)
    }
}

class BuildAndResolveFirAction : AnAction() {
    private fun createSession(moduleInfo: IdeaModuleInfo, provider: FirProjectSessionProvider): FirJavaModuleBasedSession {
        return FirJavaModuleBasedSession(
            moduleInfo,
            provider,
            moduleInfo.contentScope(),
            IdeFirDependenciesSymbolProvider(moduleInfo, provider.project, provider)
        )
    }

//    private fun createLibrarySession(moduleInfo: IdeaModuleInfo, provider: FirProjectSessionProvider): FirLibrarySession {
//        val contentScope = moduleInfo.contentScope()
//        return FirLibrarySession(moduleInfo, provider, contentScope)
//    }


    private fun runFirAnalysis(project: Project, indicator: ProgressIndicator) {
        val provider = FirProjectSessionProvider(project)
        val firFiles = mutableListOf<FirFile>()
        val allModules = project.allModules()
        indicator.text = "Rising FIR"
        for (module in allModules) {
            indicator.text2 = "In: ${module.name}"
            indicator.fraction += 1.0 / allModules.size


            for (moduleInfo in listOfNotNull(module.productionSourceInfo(), module.testSourceInfo())) {
                val session = createSession(moduleInfo, provider)

                val builder = RawFirBuilder(session)
                val psiManager = PsiManager.getInstance(project)

                val ideaModuleInfo = session.moduleInfo.cast<IdeaModuleInfo>()

//                ideaModuleInfo.dependenciesWithoutSelf().forEach {
//                    if (it is IdeaModuleInfo && it.isLibraryClasses()) {
//                        createLibrarySession(it, provider)
//                    }
//                }

                val contentScope = ideaModuleInfo.contentScope()

                runReadAction {
                    val files = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, contentScope)
                    files.forEach {
                        try {
                            val file = psiManager.findFile(it) as? KtFile ?: return@forEach
                            val firFile = builder.buildFirFile(file)
                            (session.service<FirProvider>() as FirProviderImpl).recordFile(firFile)
                            firFiles += firFile
                        } catch (t: Throwable) {
                            t.printStackTrace()
                        }
                    }
                }
            }
        }

        indicator.pushState()

        indicator.text = "Resolving"
        indicator.text2 = "..."
        indicator.fraction = 1.0

        runReadAction {
            doFirResolveTestBenchIde(firFiles, FirTotalResolveTransformer().transformers, project)
        }

        indicator.popState()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val task = object : Task.Backgroundable(project, "FIR") {
            override fun run(indicator: ProgressIndicator) {
                runFirAnalysis(project, indicator)
            }

        }
        val indicator = BackgroundableProcessIndicator(task)

        ProgressManager.getInstance().runProcessWithProgressAsynchronously(
            task,
            indicator
        )


    }
}

fun doFirResolveTestBenchIde(firFiles: List<FirFile>, transformers: List<FirTransformer<Nothing?>>, project: Project) {

    System.gc()

    val timePerTransformer = mutableMapOf<KClass<*>, Long>()
    val counterPerTransformer = mutableMapOf<KClass<*>, Long>()
    var resolvedTypes = 0
    var errorTypes = 0
    var unresolvedTypes = 0


    try {
        for (transformer in transformers) {
            for (file in firFiles) {
                val time = measureNanoTime {
                    try {
                        transformer.transformFile(file, null)
                    } catch (e: Exception) {
                        println("Fail in file: ${(file.psi as KtFile).virtualFilePath}")
                        throw e
                    }
                }
                timePerTransformer.merge(transformer::class, time) { a, b -> a + b }
                counterPerTransformer.merge(transformer::class, 1) { a, b -> a + b }
            }
        }


        println("SUCCESS!")
    } finally {

        var implicitTypes = 0
        var nonImplementedDelegatedConstructorCall = 0


        val errorTypesReports = mutableMapOf<String, String>()
        val errorTypesReportCounts = mutableMapOf<String, Int>()

        val psiDocumentManager = PsiDocumentManager.getInstance(project)

        firFiles.forEach {
            it.accept(object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    element.acceptChildren(this)
                }

                override fun visitType(type: FirType) {
                    unresolvedTypes++
                }

                override fun visitResolvedType(resolvedType: FirResolvedType) {
                    resolvedTypes++
                    val type = resolvedType.type
                    if (type is ConeKotlinErrorType || type is ConeClassErrorType) {
                        if (resolvedType.psi == null) {
                            implicitTypes++
                        } else {
                            val reason = when (type) {
                                is ConeKotlinErrorType -> type.reason
                                is ConeClassErrorType -> type.reason
                                else -> error("!!!!!!!!!!!!!")
                            }

                            if (reason == "Not implemented yet") {
                                nonImplementedDelegatedConstructorCall++
                            } else {
                                val psi = resolvedType.psi!!
                                val problem = "`$reason` with psi `${psi.text}`"
                                val document = psiDocumentManager.getDocument(psi.containingFile)
                                val line = document?.getLineNumber(psi.startOffset) ?: 0
                                val char = psi.startOffset - (document?.getLineStartOffset(line) ?: 0)
                                val report = "e: ${psi.containingFile?.virtualFile?.path}: ($line:$char): $problem"
                                errorTypesReports[problem] = report
                                errorTypesReportCounts.merge(problem, 1) { a, b -> a + b }
                                errorTypes++
                            }
                        }
                    }
                }
            })
        }

        errorTypesReports.forEach {
            println(it.value + " (total: ${errorTypesReportCounts[it.key]})")
        }


        println("UNRESOLVED TYPES: $unresolvedTypes")
        println("RESOLVED TYPES: $resolvedTypes")
        println("GOOD TYPES: ${resolvedTypes - errorTypes}")
        println("NOT IMPLEMENTED ER TYPES: $nonImplementedDelegatedConstructorCall")
        println("NOT IMPLEMENTED IMPLICIT TYPES: $implicitTypes")
        println("ERROR TYPES: $errorTypes")
        println("UNIQUE ERROR TYPES: ${errorTypesReports.size}")



        timePerTransformer.forEach { (transformer, time) ->
            val counter = counterPerTransformer[transformer]!!
            println("${transformer.simpleName}, TIME: ${time * 1e-6} ms, TIME PER FILE: ${(time / counter) * 1e-6} ms, FILES: $counter")
        }
    }
}