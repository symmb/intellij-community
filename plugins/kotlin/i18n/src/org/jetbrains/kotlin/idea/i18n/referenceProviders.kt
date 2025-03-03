// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.i18n

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.lang.properties.ResourceBundleReference
import com.intellij.lang.properties.references.PropertyReference
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.search.LocalSearchScope
import com.intellij.util.ProcessingContext
import org.jetbrains.annotations.PropertyKey
import org.jetbrains.kotlin.base.fe10.analysis.findAnnotation
import org.jetbrains.kotlin.base.fe10.analysis.getStringValue
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.annotations.Annotated
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptorIfAny
import org.jetbrains.kotlin.idea.caches.resolve.safeAnalyzeNonSourceRootCode
import org.jetbrains.kotlin.idea.imports.importableFqName
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.isPlain
import org.jetbrains.kotlin.resolve.calls.util.getParentResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ArgumentMatch
import org.jetbrains.kotlin.resolve.calls.model.ExpressionValueArgument
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver

private val PROPERTY_KEY = FqName(AnnotationUtil.PROPERTY_KEY)
private val PROPERTY_KEY_RESOURCE_BUNDLE = Name.identifier(AnnotationUtil.PROPERTY_KEY_RESOURCE_BUNDLE_PARAMETER)

private fun Annotated.getBundleNameByAnnotation(): String? {
    return findAnnotation<PropertyKey>()?.getStringValue(PropertyKey::resourceBundle)
}

private fun KtExpression.getBundleNameByContext(): String? {
    val expression = KtPsiUtil.safeDeparenthesize(this)
    val parent = expression.parent

    if (parent is KtProperty) {
        return parent.resolveToDescriptorIfAny()?.getBundleNameByAnnotation()
    }

    val bindingContext = expression.safeAnalyzeNonSourceRootCode(BodyResolveMode.PARTIAL)
    val resolvedCall = if (parent is KtQualifiedExpression && expression == parent.receiverExpression) {
        parent.selectorExpression.getResolvedCall(bindingContext)
    } else {
        expression.getParentResolvedCall(bindingContext)
    } ?: return null
    val callable = resolvedCall.resultingDescriptor

    if ((resolvedCall.extensionReceiver as? ExpressionReceiver)?.expression == expression) {
        return callable.extensionReceiverParameter?.getBundleNameByAnnotation()
    }

    return resolvedCall.valueArguments.entries
        .singleOrNull { it.value.arguments.any { it.getArgumentExpression() == expression } }
        ?.key
        ?.getBundleNameByAnnotation()
}

private fun KtAnnotationEntry.getPropertyKeyResolvedCall(): ResolvedCall<*>? {
    val resolvedCall = resolveToCall() ?: return null
    val klass = (resolvedCall.resultingDescriptor as? ClassConstructorDescriptor)?.containingDeclaration ?: return null
    if (klass.kind != ClassKind.ANNOTATION_CLASS || klass.importableFqName != PROPERTY_KEY) return null
    return resolvedCall
}

private fun KtStringTemplateExpression.isBundleName(): Boolean {
    when (val parent = KtPsiUtil.safeDeparenthesize(this).parent) {
        is KtValueArgument -> {
            val resolvedCall = parent.getStrictParentOfType<KtAnnotationEntry>()?.getPropertyKeyResolvedCall() ?: return false
            val valueParameter = (resolvedCall.getArgumentMapping(parent) as? ArgumentMatch)?.valueParameter ?: return false
            if (valueParameter.name != PROPERTY_KEY_RESOURCE_BUNDLE) return false

            return true
        }

        is KtProperty -> {
            val contexts = (parent.useScope as? LocalSearchScope)?.scope ?: arrayOf(parent.containingFile)
            return contexts.any {
                it.anyDescendantOfType<KtAnnotationEntry> f@{ entry ->
                    if (!entry.valueArguments.any { it.getArgumentName()?.asName == PROPERTY_KEY_RESOURCE_BUNDLE }) return@f false
                    val resolvedCall = entry.getPropertyKeyResolvedCall() ?: return@f false
                    val parameter =
                        resolvedCall.resultingDescriptor.valueParameters.singleOrNull { it.name == PROPERTY_KEY_RESOURCE_BUNDLE }
                            ?: return@f false
                    val valueArgument = resolvedCall.valueArguments[parameter] as? ExpressionValueArgument ?: return@f false
                    val bundleNameExpression = valueArgument.valueArgument?.getArgumentExpression() ?: return@f false
                    bundleNameExpression is KtSimpleNameExpression && bundleNameExpression.mainReference.resolve() == parent
                }
            }
        }
    }

    return false
}

object KotlinPropertyKeyReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<out PsiReference> {
        if (!(element is KtStringTemplateExpression && element.isPlain())) return PsiReference.EMPTY_ARRAY
        val bundleName = element.getBundleNameByContext() ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(PropertyReference(ElementManipulators.getValueText(element), element, bundleName, false))
    }
}

object KotlinResourceBundleNameReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<out PsiReference> {
        if (!(element is KtStringTemplateExpression && element.isPlain() && element.isBundleName())) return PsiReference.EMPTY_ARRAY
        return arrayOf(ResourceBundleReference(element))
    }
}