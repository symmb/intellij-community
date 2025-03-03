// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.refactoring.copy

import com.intellij.ide.util.DirectoryChooser
import com.intellij.java.refactoring.JavaRefactoringBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import com.intellij.refactoring.HelpID
import com.intellij.refactoring.MoveDestination
import com.intellij.refactoring.PackageWrapper
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.copy.CopyFilesOrDirectoriesDialog
import com.intellij.refactoring.ui.PackageNameReferenceEditorCombo
import com.intellij.ui.EditorTextField
import com.intellij.ui.RecentsManager
import com.intellij.ui.ReferenceEditorComboWithBrowseButton
import com.intellij.usageView.UsageViewUtil
import com.intellij.util.IncorrectOperationException
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.core.getPackage
import org.jetbrains.kotlin.idea.refactoring.Pass
import org.jetbrains.kotlin.idea.refactoring.hasIdentifiersOnly
import org.jetbrains.kotlin.idea.refactoring.ui.KotlinDestinationFolderComboBox
import org.jetbrains.kotlin.idea.roots.getSuitableDestinationSourceRoots
import org.jetbrains.kotlin.idea.util.sourceRoot
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.math.max

// Based on com.intellij.refactoring.copy.CopyClassDialog
class CopyKotlinDeclarationDialog(
    declaration: KtNamedDeclaration,
    private val defaultTargetDirectory: PsiDirectory?,
    private val project: Project
) : DialogWrapper(project, true) {
    private val informationLabel = JLabel()
    private val classNameField = EditorTextField("")
    private val packageLabel = JLabel()
    private lateinit var packageNameField: ReferenceEditorComboWithBrowseButton
    private val openInEditorCheckBox = CopyFilesOrDirectoriesDialog.createOpenInEditorCB()
    private val destinationComboBox = object : KotlinDestinationFolderComboBox() {
        override fun getTargetPackage() = packageNameField.text.trim()
        override fun reportBaseInTestSelectionInSource() = true
    }

    private val originalFile = declaration.containingFile

    var targetDirectory: MoveDestination? = null
        private set

    val targetSourceRoot: VirtualFile?
        get() = ((destinationComboBox.comboBox.selectedItem as? DirectoryChooser.ItemWrapper)?.directory ?: originalFile).sourceRoot

    init {
        informationLabel.text = JavaRefactoringBundle.message(
            "copy.class.copy.0.1",
            UsageViewUtil.getType(declaration),
            UsageViewUtil.getLongName(declaration)
        )

        informationLabel.font = informationLabel.font.deriveFont(Font.BOLD)

        init()

        destinationComboBox.setData(
            project,
            defaultTargetDirectory,
            Pass { setErrorText(it, destinationComboBox) },
            packageNameField.childComponent
        )
        classNameField.text = UsageViewUtil.getShortName(declaration)
        classNameField.selectAll()
    }

    override fun getPreferredFocusedComponent() = classNameField

    override fun createCenterPanel() = JPanel(BorderLayout())

    override fun createNorthPanel(): JComponent? {
        val qualifiedName = qualifiedName
        packageNameField =
            PackageNameReferenceEditorCombo(qualifiedName, project, RECENTS_KEY, RefactoringBundle.message("choose.destination.package"))
        packageNameField.setTextFieldPreferredWidth(max(qualifiedName.length + 5, 40))
        packageLabel.text = JavaRefactoringBundle.message("destination.package")
        packageLabel.labelFor = packageNameField

        val label = JLabel(RefactoringBundle.message("target.destination.folder"))
        val isMultipleSourceRoots = getSuitableDestinationSourceRoots(project).size > 1
        destinationComboBox.isVisible = isMultipleSourceRoots
        label.isVisible = isMultipleSourceRoots
        label.labelFor = destinationComboBox

        val panel = JPanel(BorderLayout())
        panel.add(openInEditorCheckBox, BorderLayout.EAST)
        return FormBuilder.createFormBuilder()
            .addComponent(informationLabel)
            .addLabeledComponent(RefactoringBundle.message("copy.files.new.name.label"), classNameField, UIUtil.LARGE_VGAP)
            .addLabeledComponent(packageLabel, packageNameField)
            .addLabeledComponent(label, destinationComboBox)
            .addComponent(panel)
            .panel
    }

    private val qualifiedName: String
        get() = defaultTargetDirectory?.getPackage()?.qualifiedName ?: ""

    val newName: String
        get() = classNameField.text

    val openInEditor: Boolean
        get() = openInEditorCheckBox.isSelected

    @Nls
    private fun checkForErrors(): String? {
        val packageName = packageNameField.text
        val newName = newName

        val manager = PsiManager.getInstance(project)

        if (packageName.isNotEmpty() && !FqNameUnsafe(packageName).hasIdentifiersOnly()) {
            return JavaRefactoringBundle.message("invalid.target.package.name.specified")
        }

        if (newName.isEmpty()) {
            return JavaRefactoringBundle.message("no.class.name.specified")
        }

        try {
            targetDirectory = destinationComboBox.selectDirectory(PackageWrapper(manager, packageName), false)
        } catch (e: IncorrectOperationException) {
            return e.message
        }

        targetDirectory?.getTargetIfExists(defaultTargetDirectory)?.let {
            val targetFileName = newName + "." + originalFile.virtualFile.extension
            if (it.findFile(targetFileName) == originalFile) {
                return KotlinBundle.message("error.text.can.t.copy.class.to.the.containing.file")
            }
        }

        return null
    }

    override fun doOKAction() {
        val packageName = packageNameField.text

        checkForErrors()?.let { errorString ->
            if (errorString.isNotEmpty()) {
                Messages.showMessageDialog(project, errorString, RefactoringBundle.message("error.title"), Messages.getErrorIcon())
            }
            classNameField.requestFocusInWindow()
            return
        }

        RecentsManager.getInstance(project).registerRecentEntry(RECENTS_KEY, packageName)
        CopyFilesOrDirectoriesDialog.saveOpenInEditorState(openInEditorCheckBox.isSelected)

        super.doOKAction()
    }

    override fun getHelpId() = HelpID.COPY_CLASS

    companion object {
        @NonNls
        private val RECENTS_KEY = "CopyKotlinDeclarationDialog.RECENTS_KEY"
    }
}
