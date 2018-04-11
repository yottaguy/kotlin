/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java.symbols

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.java.JavaSymbolProvider
import org.jetbrains.kotlin.fir.symbols.ConeClassSymbol
import org.jetbrains.kotlin.fir.symbols.ConeTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassErrorType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.impl.ConeClassTypeImpl
import org.jetbrains.kotlin.load.java.structure.JavaClass
import org.jetbrains.kotlin.load.java.structure.classId
import org.jetbrains.kotlin.name.ClassId

class JavaClassSymbol(val symbolProvider: JavaSymbolProvider, javaClass: JavaClass) : ConeClassSymbol {
    override val classId: ClassId = javaClass.classId ?: error("!")

    override val typeParameters: List<ConeTypeParameterSymbol> =
        javaClass.typeParameters.map { JavaTypeParameterSymbol(it.name) }

    override val kind: ClassKind = when {
        javaClass.isEnum -> ClassKind.ENUM_CLASS
        javaClass.isInterface -> ClassKind.INTERFACE
        javaClass.isAnnotationType -> ClassKind.ANNOTATION_CLASS
        else -> ClassKind.CLASS
    }

    override val superTypes: List<ConeClassLikeType> by lazy {
        javaClass.supertypes.map {
            (it.classifier as? JavaClass)?.let {
                val symbol = symbolProvider.getSymbolByJavaClass(it)
                if (symbol != null) {
                    ConeClassTypeImpl(
                        symbol as JavaClassSymbol,
                        emptyArray() // TODO: Fix this?
                    )
                } else {
                    ConeClassErrorType("Unsupported: no (null) symbol for JavaClass supertype: ${it.name}")
                }
            } ?: ConeClassErrorType("Unsupported: Non JavaClass superType in JavaClassSymbol") // TODO: Support it
        }
    }
}


