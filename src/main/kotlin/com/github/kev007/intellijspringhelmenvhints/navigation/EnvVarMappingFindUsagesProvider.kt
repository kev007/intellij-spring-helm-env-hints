package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement

/**
 * Provides language-level support for finding usages of env vars.
 * Enables the Find Usages action (Cmd+Alt+F7 on Mac, Alt+F7 on Windows) to report
 * that env var usages can be found in Spring and Helm YAML files.
 */
class EnvVarMappingFindUsagesProvider : FindUsagesProvider {

    override fun canFindUsagesFor(element: PsiElement): Boolean {
        return EnvVarMappingSupport.findUsagesTarget(element) != null
    }

    override fun getWordsScanner(): DefaultWordsScanner? = null

    override fun getHelpId(element: PsiElement): String? = null

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String {
        return element.text
    }

    override fun getDescriptiveName(element: PsiElement): String {
        return element.text
    }

    override fun getType(element: PsiElement): String {
        return "env var"
    }
}



