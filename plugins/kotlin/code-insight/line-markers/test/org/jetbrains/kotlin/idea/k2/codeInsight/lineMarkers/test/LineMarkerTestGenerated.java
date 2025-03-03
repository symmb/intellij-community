// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.k2.codeInsight.lineMarkers.test;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.idea.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.test.TestMetadata;
import org.jetbrains.kotlin.idea.base.test.TestRoot;
import org.junit.runner.RunWith;

/**
 * This class is generated by {@link org.jetbrains.kotlin.testGenerator.generator.TestGenerator}.
 * DO NOT MODIFY MANUALLY.
 */
@SuppressWarnings("all")
@TestRoot("code-insight/line-markers")
@TestDataPath("$CONTENT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
@TestMetadata("testData")
public abstract class LineMarkerTestGenerated extends AbstractLineMarkerTest {
    @RunWith(JUnit3RunnerWithInners.class)
    @TestMetadata("testData/recursive")
    public static class Recursive extends AbstractLineMarkerTest {
        @TestMetadata("callableReference.kt")
        public void testCallableReference() throws Exception {
            performTest();
        }

        @TestMetadata("companionInvoke.kt")
        public void testCompanionInvoke() throws Exception {
            performTest();
        }

        @TestMetadata("conventions.kt")
        public void testConventions() throws Exception {
            performTest();
        }

        @TestMetadata("defaultValue.kt")
        public void testDefaultValue() throws Exception {
            performTest();
        }

        @TestMetadata("dispatchExtensionReceivers.kt")
        public void testDispatchExtensionReceivers() throws Exception {
            performTest();
        }

        @TestMetadata("dispatchReceiver.kt")
        public void testDispatchReceiver() throws Exception {
            performTest();
        }

        @TestMetadata("extensionReceiver.kt")
        public void testExtensionReceiver() throws Exception {
            performTest();
        }

        @TestMetadata("functionLiteral.kt")
        public void testFunctionLiteral() throws Exception {
            performTest();
        }

        @TestMetadata("generic.kt")
        public void testGeneric() throws Exception {
            performTest();
        }

        @TestMetadata("inlineLambda.kt")
        public void testInlineLambda() throws Exception {
            performTest();
        }

        @TestMetadata("insideLambda.kt")
        public void testInsideLambda() throws Exception {
            performTest();
        }

        @TestMetadata("localClass.kt")
        public void testLocalClass() throws Exception {
            performTest();
        }

        @TestMetadata("localFunction.kt")
        public void testLocalFunction() throws Exception {
            performTest();
        }

        @TestMetadata("propertyAccessors.kt")
        public void testPropertyAccessors() throws Exception {
            performTest();
        }

        @TestMetadata("sameLine.kt")
        public void testSameLine() throws Exception {
            performTest();
        }

        @TestMetadata("simple.kt")
        public void testSimple() throws Exception {
            performTest();
        }

        @TestMetadata("super.kt")
        public void testSuper() throws Exception {
            performTest();
        }

        @TestMetadata("with.kt")
        public void testWith() throws Exception {
            performTest();
        }
    }

    @RunWith(JUnit3RunnerWithInners.class)
    @TestMetadata("testData/suspend")
    public static class Suspend extends AbstractLineMarkerTest {
        @TestMetadata("callChain.kt")
        public void testCallChain() throws Exception {
            performTest();
        }

        @TestMetadata("callableReference.kt")
        public void testCallableReference() throws Exception {
            performTest();
        }

        @TestMetadata("coroutineContext.kt")
        public void testCoroutineContext() throws Exception {
            performTest();
        }

        @TestMetadata("forLoop.kt")
        public void testForLoop() throws Exception {
            performTest();
        }

        @TestMetadata("implicitReceiver.kt")
        public void testImplicitReceiver() throws Exception {
            performTest();
        }

        @TestMetadata("import.kt")
        public void testImport() throws Exception {
            performTest();
        }

        @TestMetadata("infix.kt")
        public void testInfix() throws Exception {
            performTest();
        }

        @TestMetadata("insideSuspendLambda.kt")
        public void testInsideSuspendLambda() throws Exception {
            performTest();
        }

        @TestMetadata("invalidCall.kt")
        public void testInvalidCall() throws Exception {
            performTest();
        }

        @TestMetadata("notSuspend.kt")
        public void testNotSuspend() throws Exception {
            performTest();
        }

        @TestMetadata("parameter.kt")
        public void testParameter() throws Exception {
            performTest();
        }

        @TestMetadata("plusOperator.kt")
        public void testPlusOperator() throws Exception {
            performTest();
        }

        @TestMetadata("simple.kt")
        public void testSimple() throws Exception {
            performTest();
        }

        @TestMetadata("variable.kt")
        public void testVariable() throws Exception {
            performTest();
        }
    }
}
