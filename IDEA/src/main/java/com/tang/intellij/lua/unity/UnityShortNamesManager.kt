/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.unity

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.tang.intellij.lua.lang.LuaLanguage
import com.tang.intellij.lua.psi.LuaClass
import com.tang.intellij.lua.psi.LuaClassField
import com.tang.intellij.lua.psi.LuaClassMember
import com.tang.intellij.lua.psi.Visibility
import com.tang.intellij.lua.psi.search.LuaShortNamesManager
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.ITy
import com.tang.intellij.lua.ty.ITyClass
import com.tang.intellij.lua.ty.TyClass
import com.tang.intellij.lua.ty.createSerializedClass
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset

class UnityShortNamesManager : LuaShortNamesManager() {

    private val classList = mutableListOf<UnityClass>()
    private val classMap = mutableMapOf<String, UnityClass>()

    init {
        ApplicationManager.getApplication().executeOnPooledThread {
            createSocket()
        }
    }

    private fun DataInputStream.readMyUTF8(): String {
        val len = readMySize()
        val bytes = ByteArray(len)
        read(bytes)
        val string = String(bytes, Charset.defaultCharset())
        if (string == "UnityEngine.AndroidJavaObject") {
            println(string)
        }
        return string
    }

    private fun InputStream.readMySize(): Int {
        val ch1 = read()
        val ch2 = read()
        val ch3 = read()
        val ch4 = read()
        return (ch1 shl 0) + (ch2 shl 8) + (ch3 shl 16) + (ch4 shl 24)
    }

    private fun createSocket() {
        val project = ProjectManager.getInstance().openProjects.first()
        val mgr = PsiManager.getInstance(project)

        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress("127.0.0.1", 9988))
        val stream = socket.getInputStream()
        val streamSize = stream.readMySize()

        val dataInputStream = DataInputStream(stream)
        while (dataInputStream.available() > 0) {
            val fullName = dataInputStream.readMyUTF8()
            println(fullName)
            val aClass = UnityClass(fullName, mgr)
            classList.add(aClass)
            classMap[fullName] = aClass

            // field list
            val fieldsCount = dataInputStream.readMySize()
            for (i in 0 until fieldsCount) {
                val name = dataInputStream.readMyUTF8()
                val type = dataInputStream.readMyUTF8()
                aClass.addMember(name, type)
                println(">>> $name - $type")
            }
            // field list
            val properties = dataInputStream.readMySize()
            for (i in 0 until properties) {
                val name = dataInputStream.readMyUTF8()
                val type = dataInputStream.readMyUTF8()
                aClass.addMember(name, type)
                println(">>> $name - $type")
            }
        }
    }

    override fun findClass(name: String, context: SearchContext): LuaClass? {
        return classMap[name]
    }

    override fun findClass(name: String, project: Project, scope: GlobalSearchScope): LuaClass? {
        return classMap[name]
    }

    override fun findMember(type: ITyClass, fieldName: String, context: SearchContext): LuaClassMember? {
        val clazz = classMap[type.className] ?: return null
        return clazz.findMember(fieldName)
    }

    override fun processAllClassNames(project: Project, processor: Processor<String>) {
        classList.forEach { processor.process(it.className) }
    }

    override fun processClassesWithName(name: String, project: Project, scope: GlobalSearchScope, processor: Processor<LuaClass>) {
        findClass(name, project, scope)?.let { processor.process(it) }
    }

    override fun getClassMembers(clazzName: String, project: Project, scope: GlobalSearchScope): MutableCollection<LuaClassMember> {
        val clazz = classMap[clazzName]
        if (clazz != null)
            return clazz.members
        return mutableListOf()
    }
}

private class UnityClassMember(val fieldName: String, val type: String, val parent: UnityClass, mg: PsiManager) : LightElement(mg, LuaLanguage.INSTANCE), PsiNamedElement, LuaClassField {
    override fun toString(): String {
        return fieldName
    }

    override fun guessType(context: SearchContext): ITy {
        return createSerializedClass(type)
    }

    override fun setName(name: String): PsiElement {
        return this
    }

    override fun getName() = fieldName

    override fun guessParentType(context: SearchContext): ITy {
        return parent.type
    }

    override val visibility: Visibility
        get() = Visibility.PUBLIC
    override val isDeprecated: Boolean
        get() = false
}

private class TyUnityClass(val clazz: UnityClass) : TyClass(clazz.name) {
    override fun findMemberType(name: String, searchContext: SearchContext): ITy? {
        return clazz.findMember(name)?.guessType(searchContext)
    }

    override fun findMember(name: String, searchContext: SearchContext): LuaClassMember? {
        return clazz.findMember(name)
    }
}

private class UnityClass(val className: String, mg: PsiManager) : LightElement(mg, LuaLanguage.INSTANCE), PsiNamedElement, LuaClass {
    private val ty:ITyClass by lazy { TyUnityClass(this) }

    val members = mutableListOf<LuaClassMember>()

    override val type: ITyClass
        get() = ty

    override fun setName(name: String): PsiElement {
        return this
    }

    override fun getName() = className

    override fun toString(): String {
        return className
    }

    fun addMember(name: String, type: String) {
        val member = UnityClassMember(name, type, this, manager)
        members.add(member)
    }

    fun findMember(name: String): LuaClassMember? {
        return members.firstOrNull { it.name == name }
    }
}