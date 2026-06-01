package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EnvVarMappingReferenceContributorTest : BasePlatformTestCase() {

    fun testSpringKeyHasReferenceToHelmEnv() {
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

        val springFile = myFixture.configureByText(
            "application.yaml",
            """
            my:
              service:
                url: http://localhost
            """.trimIndent(),
        )

        val keyOffset = springFile.text.indexOf("url")
        val reference = springFile.findReferenceAt(keyOffset)
        assertNotNull(reference)
        assertEquals("MY_SERVICE_URL", reference?.resolve()?.text)
    }

    fun testHelmEnvHasReferenceToSpringKey() {
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

        val envOffset = helmFile.text.indexOf("MY_SERVICE_URL")
        val reference = helmFile.findReferenceAt(envOffset)
        assertNotNull(reference)
        assertEquals("url", reference?.resolve()?.text)
    }
}

