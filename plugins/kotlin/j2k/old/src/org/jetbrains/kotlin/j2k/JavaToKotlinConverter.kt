// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.j2k

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.progress.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.NlsContexts
import com.intellij.psi.*
import com.intellij.psi.impl.source.DummyHolder
import com.intellij.refactoring.suggested.range
import org.jetbrains.annotations.Nls
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.j2k.ast.Element
import org.jetbrains.kotlin.j2k.usageProcessing.ExternalCodeProcessor
import org.jetbrains.kotlin.j2k.usageProcessing.UsageProcessing
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.elementsInRange
import org.jetbrains.kotlin.psi.psiUtil.isAncestor
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import java.util.*


interface PostProcessor {
    fun insertImport(file: KtFile, fqName: FqName)

    val phasesCount: Int

    fun doAdditionalProcessing(
        target: JKPostProcessingTarget,
        converterContext: ConverterContext?,
        onPhaseChanged: ((Int, String) -> Unit)?
    )
}

sealed class JKPostProcessingTarget

data class JKPieceOfCodePostProcessingTarget(
    val file: KtFile,
    val rangeMarker: RangeMarker
) : JKPostProcessingTarget()


data class JKMultipleFilesPostProcessingTarget(
    val files: List<KtFile>
) : JKPostProcessingTarget()

fun JKPostProcessingTarget.elements() = when (this) {
    is JKPieceOfCodePostProcessingTarget -> runReadAction {
        val range = rangeMarker.range ?: return@runReadAction emptyList()
        file.elementsInRange(range)
    }
    is JKMultipleFilesPostProcessingTarget -> files
}

fun JKPostProcessingTarget.files() = when (this) {
    is JKPieceOfCodePostProcessingTarget -> listOf(file)
    is JKMultipleFilesPostProcessingTarget -> files
}

enum class ParseContext {
    TOP_LEVEL,
    CODE_BLOCK
}

interface ExternalCodeProcessing {
    fun prepareWriteOperation(progress: ProgressIndicator?): (List<KtFile>) -> Unit
}


data class ElementResult(val text: String, val importsToAdd: Set<FqName>, val parseContext: ParseContext)

data class Result(
    val results: List<ElementResult?>,
    val externalCodeProcessing: ExternalCodeProcessing?,
    val converterContext: ConverterContext?
)

data class FilesResult(val results: List<String>, val externalCodeProcessing: ExternalCodeProcessing?)

interface ConverterContext

abstract class JavaToKotlinConverter {
    protected abstract fun elementsToKotlin(inputElements: List<PsiElement>, processor: WithProgressProcessor): Result
    abstract fun filesToKotlin(
        files: List<PsiJavaFile>,
        postProcessor: PostProcessor,
        progress: ProgressIndicator = EmptyProgressIndicator()
    ): FilesResult

    abstract fun elementsToKotlin(inputElements: List<PsiElement>): Result
}

class OldJavaToKotlinConverter(
    private val project: Project,
    private val settings: ConverterSettings,
    private val services: JavaToKotlinConverterServices
) : JavaToKotlinConverter() {
    companion object {
        private val LOG = Logger.getInstance(JavaToKotlinConverter::class.java)
    }

    override fun filesToKotlin(
        files: List<PsiJavaFile>,
        postProcessor: PostProcessor,
        progress: ProgressIndicator
    ): FilesResult {
        val withProgressProcessor = OldWithProgressProcessor(progress, files)
        val (results, externalCodeProcessing) = ApplicationManager.getApplication().runReadAction(Computable {
            elementsToKotlin(files, withProgressProcessor)
        })


        val texts = withProgressProcessor.processItems(0.5, results.withIndex()) { pair ->
            val (i, result) = pair
            try {
                val kotlinFile = ApplicationManager.getApplication().runReadAction(Computable {
                    KtPsiFactory(project).createAnalyzableFile("dummy.kt", result!!.text, files[i])
                })

                result!!.importsToAdd.forEach { postProcessor.insertImport(kotlinFile, it) }

                AfterConversionPass(project, postProcessor).run(kotlinFile, converterContext = null, range = null, onPhaseChanged = null)

                kotlinFile.text
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (t: Throwable) {
                LOG.error(t)
                result!!.text
            }
        }

        return FilesResult(texts, externalCodeProcessing)
    }

    override fun elementsToKotlin(inputElements: List<PsiElement>, processor: WithProgressProcessor): Result {
        try {
            val usageProcessings = LinkedHashMap<PsiElement, MutableCollection<UsageProcessing>>()
            val usageProcessingCollector: (UsageProcessing) -> Unit = {
                usageProcessings.getOrPut(it.targetElement) { ArrayList() }.add(it)
            }

            fun inConversionScope(element: PsiElement) = inputElements.any { it.isAncestor(element, strict = false) }


            val intermediateResults = processor.processItems(0.25, inputElements) { inputElement ->
                Converter.create(inputElement, settings, services, ::inConversionScope, usageProcessingCollector).convert()
            }.toMutableList()

            val results = processor.processItems(0.25, intermediateResults.withIndex()) { pair ->
                val (i, result) = pair
                intermediateResults[i] = null // to not hold unused objects in the heap
                result?.let {
                    val (text, importsToAdd) = it.codeGenerator(usageProcessings)
                    ElementResult(text, importsToAdd, it.parseContext)
                }
            }

            val externalCodeProcessing = buildExternalCodeProcessing(usageProcessings, ::inConversionScope)

            return Result(results, externalCodeProcessing, null)
        } catch (e: ElementCreationStackTraceRequiredException) {
            // if we got this exception then we need to turn element creation stack traces on to get better diagnostic
            Element.saveCreationStacktraces = true
            try {
                return elementsToKotlin(inputElements)
            } finally {
                Element.saveCreationStacktraces = false
            }
        }
    }

    override fun elementsToKotlin(inputElements: List<PsiElement>): Result {
        return elementsToKotlin(inputElements, OldWithProgressProcessor.DEFAULT)
    }


    private data class ReferenceInfo(
        val reference: PsiReference,
        val target: PsiElement,
        val file: PsiFile,
        val processings: Collection<UsageProcessing>
    ) {
        val depth: Int by lazy(LazyThreadSafetyMode.NONE) { target.parentsWithSelf.takeWhile { it !is PsiFile }.count() }
        val offset: Int by lazy(LazyThreadSafetyMode.NONE) { reference.element.textRange.startOffset }
    }

    private fun buildExternalCodeProcessing(
        usageProcessings: Map<PsiElement, Collection<UsageProcessing>>,
        inConversionScope: (PsiElement) -> Boolean
    ): ExternalCodeProcessing? {
        if (usageProcessings.isEmpty()) return null

        val map: Map<PsiElement, Collection<UsageProcessing>> = usageProcessings.values
            .flatten()
            .filter { it.javaCodeProcessors.isNotEmpty() || it.kotlinCodeProcessors.isNotEmpty() }
            .groupBy { it.targetElement }
        if (map.isEmpty()) return null

        return object : ExternalCodeProcessing {
            override fun prepareWriteOperation(progress: ProgressIndicator?): (List<KtFile>) -> Unit {
                if (progress == null) error("Progress should not be null for old J2K")
                val refs = ArrayList<ReferenceInfo>()

                progress.text = KotlinJ2KBundle.message("text.searching.usages.to.update")

                for ((i, entry) in map.entries.withIndex()) {
                    val psiElement = entry.key
                    val processings = entry.value

                    progress.text2 = (psiElement as? PsiNamedElement)?.name ?: ""
                    progress.checkCanceled()

                    ProgressManager.getInstance().runProcess(
                        {
                            val searchJava = processings.any { it.javaCodeProcessors.isNotEmpty() }
                            val searchKotlin = processings.any { it.kotlinCodeProcessors.isNotEmpty() }
                            services.referenceSearcher.findUsagesForExternalCodeProcessing(psiElement, searchJava, searchKotlin)
                                .filterNot { inConversionScope(it.element) }
                                .mapTo(refs) { ReferenceInfo(it, psiElement, it.element.containingFile, processings) }
                        },
                        ProgressPortionReporter(progress, i / map.size.toDouble(), 1.0 / map.size)
                    )

                }

                return { processUsages(refs) }
            }
        }
    }

    private fun processUsages(refs: Collection<ReferenceInfo>) {
        for (fileRefs in refs.groupBy { it.file }.values) { // group by file for faster sorting
            ReferenceLoop@
            for ((reference, _, _, processings) in fileRefs.sortedWith(ReferenceComparator)) {
                val processors = when (reference.element.language) {
                    JavaLanguage.INSTANCE -> processings.flatMap { it.javaCodeProcessors }
                    KotlinLanguage.INSTANCE -> processings.flatMap { it.kotlinCodeProcessors }
                    else -> continue@ReferenceLoop
                }

                checkReferenceValid(reference, null)

                var references = listOf(reference)
                for (processor in processors) {
                    references = references.flatMap { processor.processUsage(it)?.toList() ?: listOf(it) }
                    references.forEach { checkReferenceValid(it, processor) }
                }
            }
        }
    }

    private fun checkReferenceValid(reference: PsiReference, afterProcessor: ExternalCodeProcessor?) {
        val element = reference.element
        assert(element.isValid && element.containingFile !is DummyHolder) {
            "Reference $reference got invalidated" + (if (afterProcessor != null) " after processing with $afterProcessor" else "")
        }
    }

    private object ReferenceComparator : Comparator<ReferenceInfo> {
        override fun compare(info1: ReferenceInfo, info2: ReferenceInfo): Int {
            val depth1 = info1.depth
            val depth2 = info2.depth
            if (depth1 != depth2) { // put deeper elements first to not invalidate them when processing ancestors
                return -depth1.compareTo(depth2)
            }

            // process elements with the same deepness from right to left so that right-side of assignments is not invalidated by processing of the left one
            return -info1.offset.compareTo(info2.offset)
        }
    }
}

interface WithProgressProcessor {
    fun <TInputItem, TOutputItem> processItems(
        fractionPortion: Double,
        inputItems: Iterable<TInputItem>,
        processItem: (TInputItem) -> TOutputItem
    ): List<TOutputItem>

    fun updateState(fileIndex: Int?, phase: Int, description: String)
    fun updateState(phase: Int, subPhase: Int, subPhaseCount: Int, fileIndex: Int?, description: String)
    fun <T> process(action: () -> T): T
}


class OldWithProgressProcessor(private val progress: ProgressIndicator?, private val files: List<PsiJavaFile>?) : WithProgressProcessor {
    companion object {
        val DEFAULT = OldWithProgressProcessor(null, null)
    }

    private val progressText
        @Suppress("DialogTitleCapitalization")
        @NlsContexts.ProgressText
        get() = KotlinJ2KBundle.message("text.converting.java.to.kotlin")
    private val fileCount = files?.size ?: 0
    private val fileCountText
        @Nls
        get() = KotlinJ2KBundle.message("text.files.count.0", fileCount, if (fileCount == 1) 1 else 2)
    private var fraction = 0.0
    private var pass = 1

    override fun <TInputItem, TOutputItem> processItems(
        fractionPortion: Double,
        inputItems: Iterable<TInputItem>,
        processItem: (TInputItem) -> TOutputItem
    ): List<TOutputItem> {
        val outputItems = ArrayList<TOutputItem>()
        // we use special process with EmptyProgressIndicator to avoid changing text in our progress by inheritors search inside etc
        ProgressManager.getInstance().runProcess(
            {
                progress?.text = "$progressText ($fileCountText) - ${KotlinJ2KBundle.message("text.pass.of.3", pass)}"
                progress?.isIndeterminate = false

                for ((i, item) in inputItems.withIndex()) {
                    progress?.checkCanceled()
                    progress?.fraction = fraction + fractionPortion * i / fileCount

                    progress?.text2 = files!![i].virtualFile.presentableUrl

                    outputItems.add(processItem(item))
                }

                pass++
                fraction += fractionPortion
            },
            EmptyProgressIndicator()
        )
        return outputItems
    }

    override fun <T> process(action: () -> T): T {
        throw AbstractMethodError("Should not be called for old J2K")
    }

    override fun updateState(fileIndex: Int?, phase: Int, description: String) {
        throw AbstractMethodError("Should not be called for old J2K")
    }

    override fun updateState(
        phase: Int,
        subPhase: Int,
        subPhaseCount: Int,
        fileIndex: Int?,
        description: String
    ) {
        error("Should not be called for old J2K")
    }
}

class ProgressPortionReporter(
    indicator: ProgressIndicator,
    private val start: Double,
    private val portion: Double
) : J2KDelegatingProgressIndicator(indicator) {

    init {
        fraction = 0.0
    }

    override fun start() {
        fraction = 0.0
    }

    override fun stop() {
        fraction = portion
    }

    override fun setFraction(fraction: Double) {
        super.setFraction(start + (fraction * portion))
    }

    override fun getFraction(): Double {
        return (super.getFraction() - start) / portion
    }

    override fun setText(text: String?) {
    }

    override fun setText2(text: String?) {
    }
}

// Copied from com.intellij.ide.util.DelegatingProgressIndicator
open class J2KDelegatingProgressIndicator(indicator: ProgressIndicator) : WrappedProgressIndicator, StandardProgressIndicator {
    protected val delegate: ProgressIndicator = indicator

    override fun start() = delegate.start()
    override fun stop() = delegate.stop()
    override fun isRunning() = delegate.isRunning
    override fun cancel() = delegate.cancel()
    override fun isCanceled() = delegate.isCanceled

    override fun setText(text: String?) {
        delegate.text = text
    }

    override fun getText() = delegate.text

    override fun setText2(text: String?) {
        delegate.text2 = text
    }

    override fun getText2() = delegate.text2
    override fun getFraction() = delegate.fraction

    override fun setFraction(fraction: Double) {
        delegate.fraction = fraction
    }

    override fun pushState() = delegate.pushState()
    override fun popState() = delegate.popState()
    override fun isModal() = delegate.isModal
    override fun getModalityState() = delegate.modalityState

    override fun setModalityProgress(modalityProgress: ProgressIndicator?) {
        delegate.setModalityProgress(modalityProgress)
    }

    override fun isIndeterminate() = delegate.isIndeterminate

    override fun setIndeterminate(indeterminate: Boolean) {
        delegate.isIndeterminate = indeterminate
    }

    override fun checkCanceled() = delegate.checkCanceled()
    override fun getOriginalProgressIndicator() = delegate
    override fun isPopupWasShown() = delegate.isPopupWasShown
    override fun isShowing() = delegate.isShowing
}