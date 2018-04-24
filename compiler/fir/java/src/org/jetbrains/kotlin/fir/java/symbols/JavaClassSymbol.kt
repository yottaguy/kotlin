/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java.symbols

import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.java.JavaSymbolProvider
import org.jetbrains.kotlin.fir.resolve.FirProvider
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.ConeClassSymbol
import org.jetbrains.kotlin.fir.symbols.ConeTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassErrorType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.impl.ConeClassTypeImpl
import org.jetbrains.kotlin.load.java.structure.JavaClass
import org.jetbrains.kotlin.load.java.structure.classId
import org.jetbrains.kotlin.load.java.structure.impl.JavaClassImpl
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.tail

class JavaClassSymbol(val symbolProvider: JavaSymbolProvider, val firProvider: FirProvider?, javaClass: JavaClass) : ConeClassSymbol {

    init {
        if ((javaClass as? JavaClassImpl)?.psi is KtLightClass) {
            throw AssertionError("JavaClassSymbol is built for a light class: ${javaClass.fqName}")
        }
    }

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
        javaClass.supertypes.mapNotNull {
            (it.classifier as? JavaClass)?.let { superTypeJavaClass ->
                val superTypePsiClass = (superTypeJavaClass as? JavaClassImpl)?.psi
                val symbol = if (superTypePsiClass is KtLightClass) {
                    val kotlinOrigin = superTypePsiClass.kotlinOrigin ?: return@mapNotNull null
                    val fqName = kotlinOrigin.fqName ?: return@mapNotNull null
                    val packageFqName = kotlinOrigin.containingKtFile.packageFqName
                    val classId = ClassId(
                        packageFqName,
                        fqName.tail(packageFqName),
                        false
                    )
                    firProvider?.getSymbolByFqName(classId)
                } else {
                    symbolProvider.getSymbolByJavaClass(superTypeJavaClass)
                }
                if (symbol != null) {
                    ConeClassTypeImpl(
                        symbol as ConeClassLikeSymbol,
                        emptyArray() // TODO: Provide type arguments for Java class supertypes
                    )
                } else {
                    ConeClassErrorType("Unsupported: no (null) symbol for JavaClass supertype: ${superTypeJavaClass.name}")
                }
            } ?: ConeClassErrorType("Unsupported: Non JavaClass superType in JavaClassSymbol") // TODO: Support it
        }
    }
}


