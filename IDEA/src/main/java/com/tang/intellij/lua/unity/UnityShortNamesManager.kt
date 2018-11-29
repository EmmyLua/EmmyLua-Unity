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
import com.intellij.util.containers.ContainerUtil
import com.tang.intellij.lua.lang.LuaLanguage
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.psi.search.LuaShortNamesManager
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.*
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset

class UnityShortNamesManager : LuaShortNamesManager() {

    enum class TypeKind {
        Class,
        Array,
    }

    private val classList = mutableListOf<UnityClass>()
    private val classMap = mutableMapOf<String, UnityClass>()

    init {
        ApplicationManager.getApplication().executeOnPooledThread {
            createSocket()
        }
    }

    companion object {
        private val typeMap = mapOf(
                "System.String" to "string",
                "System.Boolean" to "boolean",
                "System.Single" to "number",
                "System.Double" to "number",
                "System.Int16" to "number",
                "System.Int32" to "number",
                "System.Int64" to "number",
                "System.SByte" to "number",
                "System.UInt16" to "number",
                "System.UInt32" to "number",
                "System.UInt64" to "number",
                "System.Void" to "void"
        )

        private fun convertType(type: String): String {
            return typeMap[type] ?: type
        }
    }

    private fun DataInputStream.readMyUTF8(): String {
        val len = readMySize()
        val bytes = ByteArray(len)
        read(bytes)
        return String(bytes, Charset.defaultCharset())
    }

    private fun DataInputStream.readType(): ITy {
        val kind = readByte().toInt()
        return when (kind) {
            TypeKind.Array.ordinal -> { // array
                val base = readType()
                TyArray(base)
            }
            else -> {
                val type = readMyUTF8()
                Ty.create(convertType(type))
            }
        }
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
            if (fullName.isEmpty())
                break

            val hasBaseType = dataInputStream.readBoolean()
            var baseTypeFullName: String? = null
            if (hasBaseType) {
                baseTypeFullName = dataInputStream.readMyUTF8()
            }

            // println("class $fullName extends $baseTypeFullName")

            val aClass = UnityClass(fullName, baseTypeFullName, mgr)
            classList.add(aClass)
            classMap[fullName] = aClass

            // field list
            val fieldsCount = dataInputStream.readMySize()
            for (i in 0 until fieldsCount) {
                val name = dataInputStream.readMyUTF8()
                val type = dataInputStream.readType()
                aClass.addMember(name, type)
                //println(">>> $name - $type")
            }
            // field list
            val properties = dataInputStream.readMySize()
            for (i in 0 until properties) {
                val name = dataInputStream.readMyUTF8()
                val type = dataInputStream.readType()
                aClass.addMember(name, type)
            }
            // methods
            val methodCount = dataInputStream.readMySize()
            for (i in 0 until methodCount) {
                val paramList = mutableListOf<LuaParamInfo>()

                // name
                val name = dataInputStream.readMyUTF8()
                // parameters
                val parameterCount = dataInputStream.readMySize()
                for (j in 0 until parameterCount) {
                    val pName = dataInputStream.readMyUTF8()
                    val pType = dataInputStream.readType()
                    paramList.add(LuaParamInfo(pName, pType))
                }
                // ret
                val retType = dataInputStream.readType()

                val ty = TySerializedFunction(FunSignature(true, retType, null, paramList.toTypedArray()), emptyArray())
                aClass.addMember(name, ty)
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

    override fun processAllClassNames(project: Project, processor: Processor<String>): Boolean {
        for (clazz in classList) {
            if (!processor.process(clazz.className))
                return false
        }
        return true
    }

    override fun processClassesWithName(name: String, project: Project, scope: GlobalSearchScope, processor: Processor<LuaClass>): Boolean {
        return findClass(name, project, scope)?.let { processor.process(it) } ?: true
    }

    override fun getClassMembers(clazzName: String, project: Project, scope: GlobalSearchScope): MutableCollection<LuaClassMember> {
        val clazz = classMap[clazzName]
        if (clazz != null)
            return clazz.members
        return mutableListOf()
    }

    private fun processAllMembers(type: String, fieldName: String, context: SearchContext, processor: Processor<LuaClassMember>, deep: Boolean = true): Boolean {
        val clazz = classMap[type] ?: return true
        val continueProcess = ContainerUtil.process(clazz.members.filter { it.name == fieldName }, processor)
        if (!continueProcess)
            return false

        val baseType = clazz.baseClassName
        if (deep && baseType != null) {
            return processAllMembers(baseType, fieldName, context, processor, deep)
        }

        return true
    }

    override fun processAllMembers(type: ITyClass, fieldName: String, context: SearchContext, processor: Processor<LuaClassMember>): Boolean {
        return processAllMembers(type.className, fieldName, context, processor)
    }
}

private class UnityClassMember(val fieldName: String, val type: ITy, val parent: UnityClass, mg: PsiManager) : LightElement(mg, LuaLanguage.INSTANCE), PsiNamedElement, LuaClassField {
    override fun toString(): String {
        return fieldName
    }

    override fun guessType(context: SearchContext): ITy {
        return type
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

private class TyUnityClass(val clazz: UnityClass) : TyClass(clazz.name, clazz.name, clazz.baseClassName) {
    override fun findMemberType(name: String, searchContext: SearchContext): ITy? {
        return clazz.findMember(name)?.guessType(searchContext)
    }

    override fun findMember(name: String, searchContext: SearchContext): LuaClassMember? {
        return clazz.findMember(name)
    }
}

private class UnityClass(val className: String, val baseClassName: String?, mg: PsiManager) : LightElement(mg, LuaLanguage.INSTANCE), PsiNamedElement, LuaClass {
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

    fun addMember(name: String, type: ITy) {
        val member = UnityClassMember(name, type, this, manager)
        members.add(member)
    }

    fun addMember(name: String, type: String) {
        val member = UnityClassMember(name, Ty.create(type), this, manager)
        members.add(member)
    }

    fun findMember(name: String): LuaClassMember? {
        return members.firstOrNull { it.name == name }
    }
}