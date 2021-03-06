/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.kotlin.dsl.provider

import org.gradle.api.Project

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.SelfResolvingDependency

import org.gradle.api.internal.ClassPathRegistry
import org.gradle.api.internal.artifacts.dsl.dependencies.DependencyFactory
import org.gradle.api.internal.initialization.ClassLoaderScope

import org.gradle.internal.classloader.ClassLoaderVisitor
import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.classpath.DefaultClassPath

import org.gradle.kotlin.dsl.codegen.generateApiExtensionsJar
import org.gradle.kotlin.dsl.support.ProgressMonitor
import org.gradle.kotlin.dsl.support.minus
import org.gradle.kotlin.dsl.support.root
import org.gradle.kotlin.dsl.support.serviceOf

import org.gradle.util.GFileUtils.moveFile

import java.io.File

import java.net.URI
import java.net.URISyntaxException
import java.net.URL

import java.util.concurrent.ConcurrentHashMap


fun gradleKotlinDslOf(project: Project): List<File> =
    kotlinScriptClassPathProviderOf(project).run {
        gradleKotlinDsl.asFiles
    }


fun kotlinScriptClassPathProviderOf(project: Project) =
    project.serviceOf<KotlinScriptClassPathProvider>()


internal
typealias JarCache = (String, JarGenerator) -> File


internal
typealias JarGenerator = (File) -> Unit


private
typealias JarGeneratorWithProgress = (File, () -> Unit) -> Unit


internal
typealias JarsProvider = () -> Collection<File>


class KotlinScriptClassPathProvider(
    val classPathRegistry: ClassPathRegistry,
    val gradleApiJarsProvider: JarsProvider,
    val jarCache: JarCache,
    val progressMonitorProvider: JarGenerationProgressMonitorProvider
) {

    /**
     * Generated Gradle API jar plus supporting libraries such as groovy-all.jar and generated API extensions.
     */
    val gradleKotlinDsl: ClassPath by lazy {
        gradleApi + gradleApiExtensions + gradleKotlinDslJars
    }

    val gradleApi: ClassPath by lazy {
        DefaultClassPath.of(gradleApiJarsProvider())
    }

    /**
     * Generated extensions to the Gradle API.
     */
    val gradleApiExtensions: ClassPath by lazy {
        DefaultClassPath.of(gradleKotlinDslExtensions())
    }

    /**
     * gradle-kotlin-dsl.jar plus kotlin libraries.
     */
    val gradleKotlinDslJars: ClassPath by lazy {
        DefaultClassPath.of(gradleKotlinDslJars())
    }

    fun compilationClassPathOf(scope: ClassLoaderScope): ClassPath =
        cachedScopeCompilationClassPath.computeIfAbsent(scope, ::computeCompilationClassPath)

    private
    fun computeCompilationClassPath(scope: ClassLoaderScope): ClassPath =
        gradleKotlinDsl + exportClassPathFromHierarchyOf(scope)

    private
    fun exportClassPathFromHierarchyOf(scope: ClassLoaderScope): ClassPath {
        require(scope.isLocked) {
            "$scope must be locked before it can be used to compute a classpath!"
        }
        val fullClassPath = cachedClassLoaderClassPath.of(scope.exportClassLoader)
        val rootClassPath = cachedClassLoaderClassPath.of(scope.root.exportClassLoader)
        return fullClassPath - rootClassPath
    }

    private
    fun gradleKotlinDslExtensions(): File =
        produceFrom("kotlin-dsl-extensions") { outputFile, onProgress ->
            generateApiExtensionsJar(outputFile, gradleJars, onProgress)
        }

    private
    fun produceFrom(id: String, generate: JarGeneratorWithProgress): File =
        jarCache(id) { outputFile ->
            progressMonitorFor(outputFile, 1).use { progressMonitor ->
                generateAtomically(outputFile) { generate(it, progressMonitor::onProgress) }
            }
        }

    private
    fun generateAtomically(outputFile: File, generate: JarGenerator) {
        val tempFile = tempFileFor(outputFile)
        generate(tempFile)
        moveFile(tempFile, outputFile)
    }

    private
    fun progressMonitorFor(outputFile: File, totalWork: Int): ProgressMonitor =
        progressMonitorProvider.progressMonitorFor(outputFile, totalWork)

    private
    fun tempFileFor(outputFile: File): File =
        createTempFile(outputFile.nameWithoutExtension, outputFile.extension).apply {
            deleteOnExit()
        }

    private
    fun gradleKotlinDslJars(): List<File> =
        gradleJars.filter {
            it.name.let { isKotlinJar(it) || it.startsWith("gradle-kotlin-dsl-") }
        }

    private
    val gradleJars by lazy {
        classPathRegistry.getClassPath(gradleApiNotation.name).asFiles
    }

    private
    val cachedScopeCompilationClassPath = ConcurrentHashMap<ClassLoaderScope, ClassPath>()

    private
    val cachedClassLoaderClassPath = ClassLoaderClassPathCache()
}


internal
fun gradleApiJarsProviderFor(dependencyFactory: DependencyFactory): JarsProvider =
    { (dependencyFactory.gradleApi() as SelfResolvingDependency).resolve() }


private
fun DependencyFactory.gradleApi(): Dependency =
    createDependency(gradleApiNotation)


private
val gradleApiNotation = DependencyFactory.ClassPathNotation.GRADLE_API


private
fun isKotlinJar(name: String): Boolean =
    name.startsWith("kotlin-stdlib-")
        || name.startsWith("kotlin-reflect-")


private
class ClassLoaderClassPathCache {

    private
    val cachedClassPaths = hashMapOf<ClassLoader, ClassPath>()

    fun of(classLoader: ClassLoader): ClassPath =
        cachedClassPaths.getOrPut(classLoader) {
            classPathOf(classLoader)
        }

    private
    fun classPathOf(classLoader: ClassLoader): ClassPath {
        val classPathFiles = mutableListOf<File>()

        object : ClassLoaderVisitor() {
            override fun visitClassPath(classPath: Array<URL>) {
                classPath.forEach { url ->
                    if (url.protocol == "file") {
                        classPathFiles.add(fileFrom(url))
                    }
                }
            }

            override fun visitParent(classLoader: ClassLoader) {
                classPathFiles.addAll(of(classLoader).asFiles)
            }
        }.visit(classLoader)

        return DefaultClassPath.of(classPathFiles)
    }

    private
    fun fileFrom(url: URL) = File(toURI(url))
}


private
fun toURI(url: URL): URI =
    try {
        url.toURI()
    } catch (e: URISyntaxException) {
        URL(
            url.protocol,
            url.host,
            url.port,
            url.file.replace(" ", "%20")).toURI()
    }
