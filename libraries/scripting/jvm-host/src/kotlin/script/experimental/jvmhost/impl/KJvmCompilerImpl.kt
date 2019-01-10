/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */
package kotlin.script.experimental.jvmhost.impl

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.CharsetToolkit
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.PsiFileFactoryImpl
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.setupCommonArguments
import org.jetbrains.kotlin.cli.jvm.*
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.CompilationErrorHandler
import org.jetbrains.kotlin.codegen.KotlinCodegenFacade
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.NameUtils
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.script.KotlinScriptDefinition
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import java.util.*
import kotlin.collections.HashMap
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.starProjectedType
import kotlin.script.dependencies.ScriptContents
import kotlin.script.experimental.api.*
import kotlin.script.experimental.dependencies.DependenciesResolver
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.getMergedScriptText
import kotlin.script.experimental.host.getScriptingClass
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.impl.BridgeDependenciesResolver
import kotlin.script.experimental.jvm.jdkHome
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.util.KotlinJars
import kotlin.script.experimental.jvmhost.KJvmCompilerProxy
import kotlin.script.experimental.util.getOrError

class KJvmCompilerImpl(val hostConfiguration: ScriptingHostConfiguration) : KJvmCompilerProxy {

    override fun compile(
        script: SourceCode,
        scriptCompilationConfiguration: ScriptCompilationConfiguration
    ): ResultWithDiagnostics<CompiledScript<*>> {
        val messageCollector = ScriptDiagnosticsMessageCollector()

        fun failure(vararg diagnostics: ScriptDiagnostic): ResultWithDiagnostics.Failure =
            ResultWithDiagnostics.Failure(*messageCollector.diagnostics.toTypedArray(), *diagnostics)

        fun failure(message: String): ResultWithDiagnostics.Failure =
            ResultWithDiagnostics.Failure(
                *messageCollector.diagnostics.toTypedArray(),
                message.asErrorDiagnostics(path = script.locationId)
            )

        fun SourceCode.scriptFileName(): String = when {
            name != null -> name!!
            script == this -> "script.${scriptCompilationConfiguration[ScriptCompilationConfiguration.fileExtension]}"
            else -> throw Exception("Unexpected script without name: $this")
        }

        val disposable = Disposer.newDisposable()

        try {
            setIdeaIoUseFallback()

            var environment: KotlinCoreEnvironment? = null
            val knownSources = arrayListOf(script)
            val updatedConfigurations = HashMap<SourceCode, ScriptCompilationConfiguration>()

            fun updateConfiguration(script: SourceCode, updatedConfiguration: ScriptCompilationConfiguration) {
                updatedConfigurations[script] = updatedConfiguration
            }

            fun getScriptSource(scriptContents: ScriptContents): SourceCode? {
                val name = scriptContents.file?.name
                return knownSources.find {
                    // TODO: consider using merged text (likely should be cached)
                    // on the other hand it may become obsolete when scripting internals will be redesigned properly
                    (name != null && name == it.scriptFileName()) || it.text == scriptContents.text
                }
            }

            fun getScriptConfiguration(ktFile: KtFile): ScriptCompilationConfiguration =
                knownSources.find { ktFile.name == it.name }?.let {
                    updatedConfigurations[it]
                } ?: scriptCompilationConfiguration

            val kotlinCompilerConfiguration = createInitialCompilerConfiguration(scriptCompilationConfiguration, messageCollector).apply {
                add(
                    JVMConfigurationKeys.SCRIPT_DEFINITIONS,
                    BridgeScriptDefinition(scriptCompilationConfiguration, hostConfiguration, ::updateConfiguration, ::getScriptSource)
                )
            }

            environment = KotlinCoreEnvironment.createForProduction(
                disposable, kotlinCompilerConfiguration, EnvironmentConfigFiles.JVM_CONFIG_FILES
            )

            val psiFileFactory: PsiFileFactoryImpl = PsiFileFactory.getInstance(environment.project) as PsiFileFactoryImpl
            val scriptText = getMergedScriptText(script, scriptCompilationConfiguration)
            val virtualFile = LightVirtualFile(
                script.scriptFileName(),
                KotlinLanguage.INSTANCE,
                StringUtil.convertLineSeparators(scriptText)
            ).apply {
                charset = CharsetToolkit.UTF8_CHARSET
            }
            val psiFile: KtFile = psiFileFactory.trySetupPsiForFile(virtualFile, KotlinLanguage.INSTANCE, true, false) as KtFile?
                ?: return failure("Unable to make PSI file from script")

            val ktScript = psiFile.declarations.firstIsInstanceOrNull<KtScript>()
                ?: return failure("Not a script file")

            val sourceFiles = arrayListOf(psiFile)
            val (classpath, newSources, sourceDependencies) =
                collectScriptsCompilationDependencies(kotlinCompilerConfiguration, environment.project, sourceFiles)
            kotlinCompilerConfiguration.addJvmClasspathRoots(classpath)
            sourceFiles.addAll(newSources)

            // collectScriptsCompilationDependencies calls resolver for every file, so at this point all updated configurations are collected
            updateCompilerConfiguration(environment, scriptCompilationConfiguration, updatedConfigurations)

            val analysisResult = analyze(sourceFiles, environment)

            if (!analysisResult.shouldGenerateCode) return failure("no code to generate")
            if (analysisResult.isError() || messageCollector.hasErrors()) return failure()

            val generationState = generate(analysisResult, sourceFiles, kotlinCompilerConfiguration)

            val compiledScript = makeCompiledScript(generationState, script, ktScript, sourceDependencies, ::getScriptConfiguration)

            return ResultWithDiagnostics.Success(compiledScript, messageCollector.diagnostics)
        } catch (ex: Throwable) {
            return failure(ex.asDiagnostics(path = script.locationId))
        } finally {
            disposable.dispose()
        }
    }

    private fun createInitialCompilerConfiguration(
        scriptCompilationConfiguration: ScriptCompilationConfiguration,
        messageCollector: MessageCollector
    ): CompilerConfiguration {

        val baseArguments = K2JVMCompilerArguments()
        parseCommandLineArguments(
            scriptCompilationConfiguration[ScriptCompilationConfiguration.compilerOptions] ?: emptyList(),
            baseArguments
        )

        return org.jetbrains.kotlin.config.CompilerConfiguration().apply {

            put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
            setupCommonArguments(baseArguments)

            setupJvmSpecificArguments(baseArguments)

            val jdkHomeFromConfigurations = scriptCompilationConfiguration.getNoDefault(ScriptCompilationConfiguration.jvm.jdkHome)
                ?: hostConfiguration[ScriptingHostConfiguration.jvm.jdkHome]
            if (jdkHomeFromConfigurations != null) {
                messageCollector.report(CompilerMessageSeverity.LOGGING, "Using JDK home directory $jdkHomeFromConfigurations")
                put(JVMConfigurationKeys.JDK_HOME, jdkHomeFromConfigurations)
            } else {
                configureJdkHome(baseArguments)
            }

            put(JVMConfigurationKeys.RETAIN_OUTPUT_IN_MEMORY, true)

            val isModularJava = isModularJava()

            scriptCompilationConfiguration[ScriptCompilationConfiguration.dependencies]?.let { dependencies ->
                addJvmClasspathRoots(
                    dependencies.flatMap {
                        (it as JvmDependency).classpath
                    }
                )
            }

            configureExplicitContentRoots(baseArguments)

            if (!baseArguments.noStdlib) {
                addModularRootIfNotNull(isModularJava, "kotlin.stdlib", KotlinJars.stdlib)
                addModularRootIfNotNull(isModularJava, "kotlin.script.runtime", KotlinJars.scriptRuntimeOrNull)
            }
            // see comments about logic in CompilerConfiguration.configureStandardLibs
            if (!baseArguments.noReflect && !baseArguments.noStdlib) {
                addModularRootIfNotNull(isModularJava, "kotlin.reflect", KotlinJars.reflectOrNull)
            }

            put(CommonConfigurationKeys.MODULE_NAME, baseArguments.moduleName ?: "kotlin-script")

            configureAdvancedJvmOptions(baseArguments)
        }
    }

    private fun updateCompilerConfiguration(
        environment: KotlinCoreEnvironment,
        scriptCompilationConfiguration: ScriptCompilationConfiguration,
        updatedConfigurations: HashMap<SourceCode, ScriptCompilationConfiguration>
    ) {
        val updatedCompilerOptions =
            updatedConfigurations.flatMap { it.value[ScriptCompilationConfiguration.compilerOptions] ?: emptyList() }
        if (updatedCompilerOptions.isNotEmpty() &&
            updatedCompilerOptions != scriptCompilationConfiguration[ScriptCompilationConfiguration.compilerOptions]
        ) {

            val updatedArguments = K2JVMCompilerArguments()
            parseCommandLineArguments(
                scriptCompilationConfiguration[ScriptCompilationConfiguration.compilerOptions] ?: emptyList(),
                updatedArguments
            )

            environment.configuration.apply {
                setupCommonArguments(updatedArguments)

                setupJvmSpecificArguments(updatedArguments)

                configureAdvancedJvmOptions(updatedArguments)
            }
        }
    }

    private fun analyze(sourceFiles: Collection<KtFile>, environment: KotlinCoreEnvironment): AnalysisResult {
        val messageCollector = environment.configuration[CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY]!!

        val analyzerWithCompilerReport = AnalyzerWithCompilerReport(messageCollector, environment.configuration.languageVersionSettings)

        analyzerWithCompilerReport.analyzeAndReport(sourceFiles) {
            val project = environment.project
            TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
                project,
                sourceFiles,
                NoScopeRecordCliBindingTrace(),
                environment.configuration,
                environment::createPackagePartProvider
            )
        }
        return analyzerWithCompilerReport.analysisResult
    }

    private fun generate(
        analysisResult: AnalysisResult, sourceFiles: List<KtFile>, kotlinCompilerConfiguration: CompilerConfiguration
    ): GenerationState {
        val generationState = GenerationState.Builder(
            sourceFiles.first().project,
            ClassBuilderFactories.BINARIES,
            analysisResult.moduleDescriptor,
            analysisResult.bindingContext,
            sourceFiles,
            kotlinCompilerConfiguration
        ).build()

        KotlinCodegenFacade.compileCorrectFiles(generationState, CompilationErrorHandler.THROW_EXCEPTION)
        return generationState
    }

    private fun makeCompiledScript(
        generationState: GenerationState,
        script: SourceCode,
        ktScript: KtScript,
        sourceDependencies: List<ScriptsCompilationDependencies.SourceDependencies>,
        getScriptConfiguration: (KtFile) -> ScriptCompilationConfiguration
    ): KJvmCompiledScript<Any> {
        val scriptDependenciesStack = ArrayDeque<KtScript>()

        fun makeOtherScripts(script: KtScript): List<KJvmCompiledScript<*>> {

            // TODO: ensure that it is caught earlier (as well) since it would be more economical
            if (scriptDependenciesStack.contains(script))
                throw IllegalArgumentException("Unable to handle recursive script dependencies")
            scriptDependenciesStack.push(script)

            val containingKtFile = script.containingKtFile
            val otherScripts: List<KJvmCompiledScript<*>> =
                sourceDependencies.find { it.scriptFile == containingKtFile }?.sourceDependencies?.mapNotNull { sourceFile ->
                    sourceFile.declarations.firstIsInstanceOrNull<KtScript>()?.let {
                        KJvmCompiledScript<Any>(
                            containingKtFile.virtualFile?.path,
                            getScriptConfiguration(sourceFile),
                            it.fqName.asString(),
                            makeOtherScripts(it)
                        )
                    }
                } ?: emptyList()

            scriptDependenciesStack.pop()
            return otherScripts
        }

        return KJvmCompiledScript(
            script.locationId,
            getScriptConfiguration(ktScript.containingKtFile),
            ktScript.fqName.asString(),
            makeOtherScripts(ktScript),
            KJvmCompiledModule(generationState)
        )
    }
}

internal class ScriptDiagnosticsMessageCollector : MessageCollector {

    private val _diagnostics = arrayListOf<ScriptDiagnostic>()

    val diagnostics: List<ScriptDiagnostic> get() = _diagnostics

    override fun clear() {
        _diagnostics.clear()
    }

    override fun hasErrors(): Boolean =
        _diagnostics.any { it.severity == ScriptDiagnostic.Severity.ERROR }


    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation?) {
        val mappedSeverity = when (severity) {
            CompilerMessageSeverity.EXCEPTION,
            CompilerMessageSeverity.ERROR -> ScriptDiagnostic.Severity.ERROR
            CompilerMessageSeverity.STRONG_WARNING,
            CompilerMessageSeverity.WARNING -> ScriptDiagnostic.Severity.WARNING
            CompilerMessageSeverity.INFO -> ScriptDiagnostic.Severity.INFO
            CompilerMessageSeverity.LOGGING -> ScriptDiagnostic.Severity.DEBUG
            else -> null
        }
        if (mappedSeverity != null) {
            val mappedLocation = location?.let {
                if (it.line < 0 && it.column < 0) null // special location created by CompilerMessageLocation.create
                else SourceCode.Location(SourceCode.Position(it.line, it.column))
            }
            _diagnostics.add(ScriptDiagnostic(message, mappedSeverity, location?.path, mappedLocation))
        }
    }
}

// A bridge to the current scripting
// mostly copies functionality from KotlinScriptDefinitionAdapterFromNewAPI[Base]
// reusing it requires structural changes that doesn't seem justified now, since the internals of the scripting should be reworked soon anyway
// TODO: either finish refactoring of the scripting internals or reuse KotlinScriptDefinitionAdapterFromNewAPI[BAse] here
internal class BridgeScriptDefinition(
    val scriptCompilationConfiguration: ScriptCompilationConfiguration,
    val hostConfiguration: ScriptingHostConfiguration,
    updateConfiguration: (SourceCode, ScriptCompilationConfiguration) -> Unit,
    getScriptSource: (ScriptContents) -> SourceCode?
) : KotlinScriptDefinition(Any::class) {

    val baseClass: KClass<*> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        getScriptingClass(scriptCompilationConfiguration.getOrError(ScriptCompilationConfiguration.baseClass))
    }

    override val template: KClass<*> get() = baseClass

    override val name: String
        get() = scriptCompilationConfiguration[ScriptCompilationConfiguration.displayName] ?: "Kotlin Script"

    override val fileType: LanguageFileType = KotlinFileType.INSTANCE

    override fun isScript(fileName: String): Boolean =
        fileName.endsWith(".$fileExtension")

    override fun getScriptName(script: KtScript): Name {
        val fileBasedName = NameUtils.getScriptNameForFile(script.containingKtFile.name)
        return Name.identifier(fileBasedName.identifier.removeSuffix(".$fileExtension"))
    }

    override val fileExtension: String
        get() = scriptCompilationConfiguration[ScriptCompilationConfiguration.fileExtension] ?: super.fileExtension

    override val acceptedAnnotations = run {
        val cl = this::class.java.classLoader
        scriptCompilationConfiguration[ScriptCompilationConfiguration.refineConfigurationOnAnnotations]?.annotations
            ?.map { (cl.loadClass(it.typeName) as Class<out Annotation>).kotlin }
            ?: emptyList()
    }

    override val implicitReceivers: List<KType> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        scriptCompilationConfiguration[ScriptCompilationConfiguration.implicitReceivers]
            .orEmpty()
            .map { getScriptingClass(it).starProjectedType }
    }

    override val providedProperties: List<Pair<String, KType>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        scriptCompilationConfiguration[ScriptCompilationConfiguration.providedProperties]
            ?.map { (k, v) -> k to getScriptingClass(v).starProjectedType }.orEmpty()
    }

    override val additionalCompilerArguments: List<String>
        get() = scriptCompilationConfiguration[ScriptCompilationConfiguration.compilerOptions]
            .orEmpty()

    override val dependencyResolver: DependenciesResolver =
        BridgeDependenciesResolver(scriptCompilationConfiguration, updateConfiguration, getScriptSource)

    private val scriptingClassGetter by lazy(LazyThreadSafetyMode.PUBLICATION) {
        hostConfiguration[ScriptingHostConfiguration.getScriptingClass]
            ?: throw IllegalArgumentException("Expecting 'getScriptingClass' property in the scripting environment")
    }

    private fun getScriptingClass(type: KotlinType) =
        scriptingClassGetter(
            type,
            KotlinScriptDefinition::class, // Assuming that the KotlinScriptDefinition class is loaded in the proper classloader
            hostConfiguration
        )
}
