// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.intentions.branchedTransformations

import org.jetbrains.kotlin.cfg.WhenChecker
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.safeAnalyzeNonSourceRootCode
import org.jetbrains.kotlin.idea.base.psi.replaced
import org.jetbrains.kotlin.idea.intentions.branches
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.lastBlockStatementOrThis
import org.jetbrains.kotlin.resolve.calls.util.getType
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeConstructor
import org.jetbrains.kotlin.types.typeUtil.isNothing
import org.jetbrains.kotlin.types.typeUtil.isSubtypeOf

object BranchedFoldingUtils {
    private fun getFoldableBranchedAssignment(branch: KtExpression?): KtBinaryExpression? {
        fun checkAssignment(expression: KtBinaryExpression): Boolean {
            if (expression.operationToken !in KtTokens.ALL_ASSIGNMENTS) return false

            val left = expression.left as? KtNameReferenceExpression ?: return false
            if (expression.right == null) return false

            val parent = expression.parent
            if (parent is KtBlockExpression) {
                return !KtPsiUtil.checkVariableDeclarationInBlock(parent, left.text)
            }

            return true
        }
        return (branch?.lastBlockStatementOrThis() as? KtBinaryExpression)?.takeIf(::checkAssignment)
    }

    fun getFoldableBranchedReturn(branch: KtExpression?): KtReturnExpression? =
        (branch?.lastBlockStatementOrThis() as? KtReturnExpression)?.takeIf {
            it.returnedExpression != null &&
                    it.returnedExpression !is KtLambdaExpression &&
                    it.getTargetLabel() == null
        }

    private fun KtBinaryExpression.checkAssignmentsMatch(
        other: KtBinaryExpression,
        leftType: KotlinType,
        rightTypeConstructor: TypeConstructor
    ): Boolean {
        val left = this.left ?: return false
        val otherLeft = other.left ?: return false
        if (left.text != otherLeft.text || operationToken != other.operationToken ||
            left.mainReference?.resolve() != otherLeft.mainReference?.resolve()
        ) return false
        val rightType = other.rightType() ?: return false
        return rightType.constructor == rightTypeConstructor || (operationToken == KtTokens.EQ && rightType.isSubtypeOf(leftType))
    }

    private fun KtBinaryExpression.rightType(): KotlinType? {
        val right = this.right ?: return null
        val context = this.analyze()
        val diagnostics = context.diagnostics
        fun hasTypeMismatchError(e: KtExpression) = diagnostics.forElement(e).any { it.factory == Errors.TYPE_MISMATCH }
        if (hasTypeMismatchError(this) || hasTypeMismatchError(right)) return null
        return right.getType(context)
    }

    internal fun getFoldableAssignmentNumber(expression: KtExpression?): Int {
        expression ?: return -1
        val assignments = linkedSetOf<KtBinaryExpression>()
        fun collectAssignmentsAndCheck(e: KtExpression?): Boolean = when (e) {
            is KtWhenExpression -> {
                val entries = e.entries
                !e.hasMissingCases() && entries.isNotEmpty() && entries.all { entry ->
                    val assignment = getFoldableBranchedAssignment(entry.expression)?.run { assignments.add(this) }
                    assignment != null || collectAssignmentsAndCheck(entry.expression?.lastBlockStatementOrThis())
                }
            }
            is KtIfExpression -> {
                val branches = e.branches
                val elseBranch = branches.lastOrNull()?.getStrictParentOfType<KtIfExpression>()?.`else`
                branches.size > 1 && elseBranch != null && branches.all { branch ->
                    val assignment = getFoldableBranchedAssignment(branch)?.run { assignments.add(this) }
                    assignment != null || collectAssignmentsAndCheck(branch?.lastBlockStatementOrThis())
                }
            }
            is KtTryExpression -> {
                e.tryBlockAndCatchBodies().all {
                    val assignment = getFoldableBranchedAssignment(it)?.run { assignments.add(this) }
                    assignment != null || collectAssignmentsAndCheck(it?.lastBlockStatementOrThis())
                }
            }
            is KtCallExpression -> {
                e.analyze().getType(e)?.isNothing() ?: false
            }
            is KtBreakExpression, is KtContinueExpression,
            is KtThrowExpression, is KtReturnExpression -> true
            else -> false
        }
        if (!collectAssignmentsAndCheck(expression)) return -1
        val firstAssignment = assignments.firstOrNull { !it.right.isNullExpression() } ?: assignments.firstOrNull() ?: return 0
        val leftType = firstAssignment.left?.let { it.getType(it.analyze(BodyResolveMode.PARTIAL)) } ?: return 0
        val rightTypeConstructor = firstAssignment.rightType()?.constructor ?: return -1
        if (assignments.any { !firstAssignment.checkAssignmentsMatch(it, leftType, rightTypeConstructor) }) {
            return -1
        }
        if (expression.anyDescendantOfType<KtBinaryExpression>(
                predicate = {
                    if (it.operationToken in KtTokens.ALL_ASSIGNMENTS)
                        if (it.getNonStrictParentOfType<KtFinallySection>() != null)
                            firstAssignment.checkAssignmentsMatch(it, leftType, rightTypeConstructor)
                        else
                            it !in assignments
                    else
                        false
                }
            )
        ) {
            return -1
        }
        return assignments.size
    }

    private fun getFoldableReturns(branches: List<KtExpression?>): List<KtReturnExpression>? =
        branches.fold<KtExpression?, MutableList<KtReturnExpression>?>(mutableListOf()) { prevList, branch ->
            if (prevList == null) return@fold null
            val foldableBranchedReturn = getFoldableBranchedReturn(branch)
            if (foldableBranchedReturn != null) {
                prevList.add(foldableBranchedReturn)
            } else {
                val currReturns = getFoldableReturns(branch?.lastBlockStatementOrThis()) ?: return@fold null
                prevList += currReturns
            }
            prevList
        }

    internal fun getFoldableReturns(expression: KtExpression?): List<KtReturnExpression>? = when (expression) {
        is KtWhenExpression -> {
            val entries = expression.entries
            when {
                expression.hasMissingCases() -> null
                entries.isEmpty() -> null
                else -> getFoldableReturns(entries.map { it.expression })
            }
        }
        is KtIfExpression -> {
            val branches = expression.branches
            when {
                branches.isEmpty() -> null
                branches.lastOrNull()?.getStrictParentOfType<KtIfExpression>()?.`else` == null -> null
                else -> getFoldableReturns(branches)
            }
        }
        is KtTryExpression -> {
            if (expression.finallyBlock?.finalExpression?.let { getFoldableReturns(listOf(it)) }?.isNotEmpty() == true)
                null
            else
                getFoldableReturns(expression.tryBlockAndCatchBodies())
        }
        is KtCallExpression -> {
            if (expression.analyze().getType(expression)?.isNothing() == true) emptyList() else null
        }
        is KtBreakExpression, is KtContinueExpression, is KtThrowExpression -> emptyList()
        else -> null
    }

    private fun getFoldableReturnNumber(expression: KtExpression?) = getFoldableReturns(expression)?.size ?: -1

    fun canFoldToReturn(expression: KtExpression?): Boolean = getFoldableReturnNumber(expression) > 0

    fun tryFoldToAssignment(expression: KtExpression) {
        var lhs: KtExpression? = null
        var op: String? = null
        val psiFactory = KtPsiFactory(expression)
        fun KtBinaryExpression.replaceWithRHS() {
            if (lhs == null || op == null) {
                lhs = left!!.copy() as KtExpression
                op = operationReference.text
            }

            val rhs = right!!
            if (rhs is KtLambdaExpression && this.parent !is KtBlockExpression) {
                replace(psiFactory.createSingleStatementBlock(rhs))
            } else {
                replace(rhs)
            }
        }

        fun lift(e: KtExpression?) {
            when (e) {
                is KtWhenExpression -> e.entries.forEach { entry ->
                    getFoldableBranchedAssignment(entry.expression)?.replaceWithRHS() ?: lift(entry.expression?.lastBlockStatementOrThis())
                }
                is KtIfExpression -> e.branches.forEach { branch ->
                    getFoldableBranchedAssignment(branch)?.replaceWithRHS() ?: lift(branch?.lastBlockStatementOrThis())
                }
                is KtTryExpression -> e.tryBlockAndCatchBodies().forEach {
                    getFoldableBranchedAssignment(it)?.replaceWithRHS() ?: lift(it?.lastBlockStatementOrThis())
                }
            }
        }
        lift(expression)
        if (lhs != null && op != null) {
            expression.replace(psiFactory.createExpressionByPattern("$0 $1 $2", lhs!!, op!!, expression))
        }
    }

    fun foldToReturn(expression: KtExpression): KtExpression {
        fun KtReturnExpression.replaceWithReturned() {
            replace(returnedExpression!!)
        }

        fun lift(e: KtExpression?) {
            when (e) {
                is KtWhenExpression -> e.entries.forEach { entry ->
                    val entryExpr = entry.expression
                    getFoldableBranchedReturn(entryExpr)?.replaceWithReturned() ?: lift(entryExpr?.lastBlockStatementOrThis())
                }
                is KtIfExpression -> e.branches.forEach { branch ->
                    getFoldableBranchedReturn(branch)?.replaceWithReturned() ?: lift(branch?.lastBlockStatementOrThis())
                }
                is KtTryExpression -> e.tryBlockAndCatchBodies().forEach {
                    getFoldableBranchedReturn(it)?.replaceWithReturned() ?: lift(it?.lastBlockStatementOrThis())
                }
            }
        }
        lift(expression)
        return expression.replaced(KtPsiFactory(expression).createExpressionByPattern("return $0", expression))
    }

    private fun KtTryExpression.tryBlockAndCatchBodies(): List<KtExpression?> = listOf(tryBlock) + catchClauses.map { it.catchBody }

    private fun KtWhenExpression.hasMissingCases(): Boolean =
        !KtPsiUtil.checkWhenExpressionHasSingleElse(this) && WhenChecker.getMissingCases(this, safeAnalyzeNonSourceRootCode()).isNotEmpty()

}
