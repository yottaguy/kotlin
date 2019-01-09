/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.symbols

import org.jetbrains.kotlin.name.ClassId

class UnresolvedClassLikeSymbol(override val classId: ClassId) : ConeClassLikeSymbol, ConeUnresolvedSymbol {
    override val typeParameters: List<ConeTypeParameterSymbol>
        get() = emptyList()
}