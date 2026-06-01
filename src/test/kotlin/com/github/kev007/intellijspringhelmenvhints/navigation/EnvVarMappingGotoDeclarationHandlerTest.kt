package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EnvVarMappingGotoDeclarationHandlerTest : BasePlatformTestCase() {

    fun testGoToDeclarationFromSpringPropertyToHelmEnv() {
        myFixture.addFileToProject(
            "chart/templates/deployment.yaml",
            """
            apiVersion: apps/v1
            kind: Deployment
            spec:
              template:
                spec:
                  containers:
                    - name: app
                      env:
                        - name: MY_SERVICE_URL
                          value: example
            """.trimIndent(),
        )

        myFixture.configureByText(
            "application.yaml",
            """
            my:
              service:
                u<caret>rl: http://localhost
            """.trimIndent(),
        )

        val offset = myFixture.editor.caretModel.offset
        val source = myFixture.file.findElementAt(offset) ?: myFixture.file.findElementAt(offset - 1)
        val targets = EnvVarMappingGotoDeclarationHandler().getGotoDeclarationTargets(source, offset, myFixture.editor)
        assertNotNull(targets)
        assertEquals("MY_SERVICE_URL", targets?.singleOrNull()?.text)
    }

    fun testGoToDeclarationFromHelmEnvToSpringProperty() {
        myFixture.addFileToProject(
            "application.yaml",
            """
            my:
              service:
                url: http://localhost
            """.trimIndent(),
        )

        val helmFile = myFixture.addFileToProject(
            "chart/templates/statefulset.yaml",
            """
            apiVersion: apps/v1
            kind: StatefulSet
            spec:
              template:
                spec:
                  containers:
                    - name: app
                      env:
                        - name: MY_SERVICE_URL
                          value: example
            """.trimIndent(),
        )

        myFixture.openFileInEditor(helmFile.virtualFile)
        val caretOffset = myFixture.file.text.indexOf("MY_SERVICE_URL") + "MY_SERVICE_".length
        myFixture.editor.caretModel.moveToOffset(caretOffset)

        val offset = myFixture.editor.caretModel.offset
        val source = myFixture.file.findElementAt(offset) ?: myFixture.file.findElementAt(offset - 1)
        val targets = EnvVarMappingGotoDeclarationHandler().getGotoDeclarationTargets(source, offset, myFixture.editor)
        assertNotNull(targets)
        assertEquals("url", targets?.singleOrNull()?.text)
    }
}





