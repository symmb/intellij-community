// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.uast.kotlin.internal

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.codegen.ClassBuilderMode
import org.jetbrains.kotlin.codegen.state.KotlinTypeMapper
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension
import org.jetbrains.uast.kotlin.KotlinUastResolveProviderService

class CliKotlinUastResolveProviderService : KotlinUastResolveProviderService {

    private val Project.analysisCompletedHandler: UastAnalysisHandlerExtension?
        get() = getExtensions(AnalysisHandlerExtension.extensionPointName)
                .filterIsInstance<UastAnalysisHandlerExtension>()
                .firstOrNull()

    @Deprecated("For binary compatibility, please, use KotlinUastTypeMapper")
    override fun getTypeMapper(element: KtElement): KotlinTypeMapper? {
        @Suppress("DEPRECATION")
        return element.project.analysisCompletedHandler?.getTypeMapper()
    }

    override fun getBindingContext(element: KtElement): BindingContext {
        return element.project.analysisCompletedHandler?.getBindingContext() ?: BindingContext.EMPTY
    }

    override fun isJvmElement(psiElement: PsiElement) = true

    override fun getLanguageVersionSettings(element: KtElement): LanguageVersionSettings {
        return element.project.analysisCompletedHandler?.getLanguageVersionSettings() ?: LanguageVersionSettingsImpl.DEFAULT
    }

    override fun getReferenceVariants(ktExpression: KtExpression, nameHint: String): Sequence<PsiElement> =
        emptySequence() // Not supported
}

class UastAnalysisHandlerExtension : AnalysisHandlerExtension {
    private var context: BindingContext? = null
    private var typeMapper: KotlinTypeMapper? = null
    private var languageVersionSettings: LanguageVersionSettings? = null

    fun getBindingContext() = context

    fun getLanguageVersionSettings() = languageVersionSettings

    @Deprecated("For binary compatibility, please, use KotlinUastTypeMapper")
    fun getTypeMapper(): KotlinTypeMapper? {
        if (typeMapper != null) return typeMapper
        val bindingContext = context ?: return null

        val typeMapper = KotlinTypeMapper(
            bindingContext, ClassBuilderMode.LIGHT_CLASSES,
            JvmProtoBufUtil.DEFAULT_MODULE_NAME,
            KotlinTypeMapper.LANGUAGE_VERSION_SETTINGS_DEFAULT, // TODO use proper LanguageVersionSettings
            useOldInlineClassesManglingScheme = false
        )
        this.typeMapper = typeMapper
        return typeMapper
    }

    override fun doAnalysis(
        project: Project,
        module: ModuleDescriptor,
        projectContext: ProjectContext,
        files: Collection<KtFile>,
        bindingTrace: BindingTrace,
        componentProvider: ComponentProvider
    ): AnalysisResult? {
        languageVersionSettings = componentProvider.get<LanguageVersionSettings>()
        return super.doAnalysis(project, module, projectContext, files, bindingTrace, componentProvider)
    }

    override fun analysisCompleted(
            project: Project,
            module: ModuleDescriptor,
            bindingTrace: BindingTrace,
            files: Collection<KtFile>
    ): AnalysisResult? {
        context = bindingTrace.bindingContext
        return null
    }
}
