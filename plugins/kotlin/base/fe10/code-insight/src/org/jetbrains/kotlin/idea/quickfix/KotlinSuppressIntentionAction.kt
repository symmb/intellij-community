// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.quickfix

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview
import com.intellij.codeInspection.SuppressIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.idea.base.fe10.codeInsight.KotlinBaseFe10CodeInsightBundle
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.highlighter.AnnotationHostKind
import org.jetbrains.kotlin.idea.util.addAnnotation
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.createSmartPointer
import org.jetbrains.kotlin.psi.psiUtil.replaceFileAnnotationList
import org.jetbrains.kotlin.resolve.BindingContext

class KotlinSuppressIntentionAction(
    suppressAt: KtElement,
    private val suppressionKey: String,
    @SafeFieldForPreview private val kind: AnnotationHostKind
) : SuppressIntentionAction() {
    private val pointer = suppressAt.createSmartPointer()

    @SafeFieldForPreview
    private val project = suppressAt.project

    override fun getFamilyName() = KotlinBaseFe10CodeInsightBundle.message("intention.suppress.family")
    override fun getText() = KotlinBaseFe10CodeInsightBundle.message("intention.suppress.text", suppressionKey, kind.kind, kind.name ?: "")

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement) = element.isValid

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        if (!element.isValid) return
        val suppressAt = pointer.element ?: return

        val id = "\"$suppressionKey\""
        when (suppressAt) {
            is KtModifierListOwner -> suppressAt.addAnnotation(
                StandardNames.FqNames.suppress,
                id,
                whiteSpaceText = if (kind.newLineNeeded) "\n" else " ",
                addToExistingAnnotation = { entry ->
                    addArgumentToSuppressAnnotation(
                        entry,
                        id
                    ); true
                })

            is KtAnnotatedExpression ->
                suppressAtAnnotatedExpression(CaretBox(suppressAt, editor), id)

            is KtExpression ->
                suppressAtExpression(CaretBox(suppressAt, editor), id)

            is KtFile ->
                suppressAtFile(suppressAt, id)
        }
    }

    override fun getFileModifierForPreview(target: PsiFile): KotlinSuppressIntentionAction {
        return KotlinSuppressIntentionAction(
            PsiTreeUtil.findSameElementInCopy(pointer.element, target),
            suppressionKey,
            kind
        )
    }

    private fun suppressAtFile(ktFile: KtFile, id: String) {
        val psiFactory = KtPsiFactory(project)

        val fileAnnotationList: KtFileAnnotationList? = ktFile.fileAnnotationList
        if (fileAnnotationList == null) {
            val newAnnotationList = psiFactory.createFileAnnotationListWithAnnotation(suppressAnnotationText(id, false))
            val packageDirective = ktFile.packageDirective
            val createAnnotationList = if (packageDirective != null && PsiTreeUtil.skipWhitespacesForward(packageDirective) == ktFile.importList) {
                // packageDirective could be empty but suppression still should be added before it to generate consistent PSI
                ktFile.addBefore(newAnnotationList, packageDirective) as KtFileAnnotationList
            } else {
                replaceFileAnnotationList(ktFile, newAnnotationList)
            }
            ktFile.addAfter(psiFactory.createWhiteSpace(kind), createAnnotationList)

            return
        }

        val suppressAnnotation: KtAnnotationEntry? = findSuppressAnnotation(fileAnnotationList)
        if (suppressAnnotation == null) {
            val newSuppressAnnotation = psiFactory.createFileAnnotation(suppressAnnotationText(id, false))
            fileAnnotationList.add(psiFactory.createWhiteSpace(kind))
            fileAnnotationList.add(newSuppressAnnotation) as KtAnnotationEntry

            return
        }

        addArgumentToSuppressAnnotation(suppressAnnotation, id)
    }

    private fun suppressAtAnnotatedExpression(suppressAt: CaretBox<KtAnnotatedExpression>, id: String) {
        val entry = findSuppressAnnotation(suppressAt.expression)
        if (entry != null) {
            // already annotated with @suppress
            addArgumentToSuppressAnnotation(entry, id)
        } else {
            suppressAtExpression(suppressAt, id)
        }
    }

    private fun suppressAtExpression(caretBox: CaretBox<KtExpression>, id: String) {
        val suppressAt = caretBox.expression
        assert(suppressAt !is KtDeclaration) { "Declarations should have been checked for above" }

        val placeholderText = "PLACEHOLDER_ID"
        val annotatedExpression = KtPsiFactory(suppressAt).createExpression(suppressAnnotationText(id) + "\n" + placeholderText)

        val copy = suppressAt.copy()!!

        val afterReplace = suppressAt.replace(annotatedExpression) as KtAnnotatedExpression
        val toReplace = afterReplace.findElementAt(afterReplace.textLength - 2)!!
        assert(toReplace.text == placeholderText)
        val result = toReplace.replace(copy)!!

        caretBox.positionCaretInCopy(result)
    }

    private fun addArgumentToSuppressAnnotation(entry: KtAnnotationEntry, id: String) {
        // add new arguments to an existing entry
        val args = entry.valueArgumentList
        val psiFactory = KtPsiFactory(entry)
        val newArgList = psiFactory.createCallArguments("($id)")
        when {
            args == null -> // new argument list
                entry.addAfter(newArgList, entry.lastChild)
            args.arguments.isEmpty() -> // replace '()' with a new argument list
                args.replace(newArgList)
            else -> args.addArgument(newArgList.arguments[0])
        }
    }

    private fun suppressAnnotationText(id: String, withAt: Boolean = true): String {
        return "${if (withAt) "@" else ""}${StandardNames.FqNames.suppress.shortName()}($id)"
    }

    private fun findSuppressAnnotation(annotated: KtAnnotated): KtAnnotationEntry? {
        val context = annotated.analyze()
        return findSuppressAnnotation(context, annotated.annotationEntries)
    }

    private fun findSuppressAnnotation(annotationList: KtFileAnnotationList): KtAnnotationEntry? {
        val context = annotationList.analyze()
        return findSuppressAnnotation(context, annotationList.annotationEntries)
    }

    private fun findSuppressAnnotation(context: BindingContext, annotationEntries: List<KtAnnotationEntry>): KtAnnotationEntry? {
        return annotationEntries.firstOrNull { entry ->
            context.get(BindingContext.ANNOTATION, entry)?.fqName == StandardNames.FqNames.suppress
        }
    }
}

private fun KtPsiFactory.createWhiteSpace(kind: AnnotationHostKind): PsiElement {
    return if (kind.newLineNeeded) createNewLine() else createWhiteSpace()
}

private class CaretBox<out E : KtExpression>(
    val expression: E,
    private val editor: Editor?
) {
    private val offsetInExpression: Int = (editor?.caretModel?.offset ?: 0) - expression.textRange!!.startOffset

    fun positionCaretInCopy(copy: PsiElement) {
        if (editor == null) return
        editor.caretModel.moveToOffset(copy.textOffset + offsetInExpression)
    }
}
