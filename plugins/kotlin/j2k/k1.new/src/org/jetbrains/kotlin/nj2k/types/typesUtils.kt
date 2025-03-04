// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.nj2k.types

import com.intellij.psi.*
import com.intellij.psi.impl.compiled.ClsMethodImpl
import com.intellij.psi.impl.source.PsiAnnotationMethodImpl
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.idea.base.psi.kotlinFqName
import org.jetbrains.kotlin.idea.base.utils.fqname.fqName
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.util.getParameterDescriptor
import org.jetbrains.kotlin.idea.search.usagesSearch.descriptor
import org.jetbrains.kotlin.j2k.Nullability
import org.jetbrains.kotlin.j2k.Nullability.Default
import org.jetbrains.kotlin.j2k.Nullability.NotNull
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.nj2k.JKSymbolProvider
import org.jetbrains.kotlin.nj2k.symbols.JKClassSymbol
import org.jetbrains.kotlin.nj2k.symbols.JKMethodSymbol
import org.jetbrains.kotlin.nj2k.symbols.JKSymbol
import org.jetbrains.kotlin.nj2k.tree.*
import org.jetbrains.kotlin.nj2k.tree.JKClass.ClassKind.ENUM
import org.jetbrains.kotlin.nj2k.types.JKVarianceTypeParameterType.Variance
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.jvm.JvmPrimitiveType
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import java.util.*

fun JKType.asTypeElement(annotationList: JKAnnotationList = JKAnnotationList()) =
    JKTypeElement(this, annotationList)

fun JKClassSymbol.asType(nullability: Nullability = Default): JKClassType =
    JKClassType(this, nullability = nullability)

val PsiType.isKotlinFunctionalType: Boolean
    get() {
        val fqName = safeAs<PsiClassType>()?.resolve()?.kotlinFqName ?: return false
        return functionalTypeRegex.matches(fqName.asString())
    }

fun PsiParameter.typeFqName(): FqName? = this.getParameterDescriptor()?.type?.fqName

fun KtParameter.typeFqName(): FqName? = this.descriptor?.type?.fqName

private val functionalTypeRegex = """(kotlin\.jvm\.functions|kotlin)\.Function[\d+]""".toRegex()

fun KtTypeReference.toJK(typeFactory: JKTypeFactory): JKType? =
    analyze(BodyResolveMode.PARTIAL)
        .get(BindingContext.TYPE, this)
        ?.let { typeFactory.fromKotlinType(it) }


infix fun JKJavaPrimitiveType.isStrongerThan(other: JKJavaPrimitiveType) =
    jvmPrimitiveTypesPriority.getValue(this.jvmPrimitiveType.primitiveType) >
            jvmPrimitiveTypesPriority.getValue(other.jvmPrimitiveType.primitiveType)

private val jvmPrimitiveTypesPriority =
    mapOf(
        PrimitiveType.BOOLEAN to -1,
        PrimitiveType.CHAR to 0,
        PrimitiveType.BYTE to 1,
        PrimitiveType.SHORT to 2,
        PrimitiveType.INT to 3,
        PrimitiveType.LONG to 4,
        PrimitiveType.FLOAT to 5,
        PrimitiveType.DOUBLE to 6
    )

fun JKType.applyRecursive(transform: (JKType) -> JKType?): JKType =
    transform(this) ?: when (this) {
        is JKTypeParameterType -> this
        is JKClassType ->
            JKClassType(
                classReference,
                parameters.map { it.applyRecursive(transform) },
                nullability
            )

        is JKNoType -> this
        is JKJavaVoidType -> this
        is JKJavaPrimitiveType -> this
        is JKJavaArrayType -> JKJavaArrayType(type.applyRecursive(transform), nullability)
        is JKContextType -> JKContextType
        is JKJavaDisjunctionType ->
            JKJavaDisjunctionType(disjunctions.map { it.applyRecursive(transform) }, nullability)

        is JKStarProjectionType -> this
        else -> this
    }

inline fun <reified T : JKType> T.updateNullability(newNullability: Nullability): T =
    if (nullability == newNullability) this
    else when (this) {
        is JKTypeParameterType -> JKTypeParameterType(identifier, newNullability)
        is JKClassType -> JKClassType(classReference, parameters, newNullability)
        is JKNoType -> this
        is JKJavaVoidType -> this
        is JKJavaPrimitiveType -> this
        is JKJavaArrayType -> JKJavaArrayType(type, newNullability)
        is JKContextType -> JKContextType
        is JKJavaDisjunctionType -> this
        else -> this
    } as T

@Suppress("UNCHECKED_CAST")
fun <T : JKType> T.updateNullabilityRecursively(newNullability: Nullability): T =
    applyRecursive { type ->
        when (type) {
            is JKTypeParameterType -> JKTypeParameterType(type.identifier, newNullability)
            is JKClassType ->
                JKClassType(
                    type.classReference,
                    type.parameters.map { it.updateNullabilityRecursively(newNullability) },
                    newNullability
                )

            is JKJavaArrayType -> JKJavaArrayType(type.type.updateNullabilityRecursively(newNullability), newNullability)
            else -> null
        }
    } as T

fun JKType.isStringType(): Boolean =
    (this as? JKClassType)?.classReference?.isStringType() == true

fun JKClassSymbol.isStringType(): Boolean =
    fqName == CommonClassNames.JAVA_LANG_STRING
            || fqName == StandardNames.FqNames.string.asString()

fun JKSymbol.isEnumType(): Boolean =
    when (val target = target) {
        is JKClass -> target.classKind == ENUM
        is KtClass -> target.isEnum()
        is PsiClass -> target.isEnum
        else -> false
    }

fun JKJavaPrimitiveType.toLiteralType(): JKLiteralExpression.LiteralType? =
    when (this) {
        JKJavaPrimitiveType.CHAR -> JKLiteralExpression.LiteralType.CHAR
        JKJavaPrimitiveType.BOOLEAN -> JKLiteralExpression.LiteralType.BOOLEAN
        JKJavaPrimitiveType.INT -> JKLiteralExpression.LiteralType.INT
        JKJavaPrimitiveType.LONG -> JKLiteralExpression.LiteralType.LONG
        JKJavaPrimitiveType.DOUBLE -> JKLiteralExpression.LiteralType.DOUBLE
        JKJavaPrimitiveType.FLOAT -> JKLiteralExpression.LiteralType.FLOAT
        else -> null
    }

fun JKType.asPrimitiveType(): JKJavaPrimitiveType? =
    if (this is JKJavaPrimitiveType) this
    else when (fqName) {
        StandardNames.FqNames._char.asString(), CommonClassNames.JAVA_LANG_CHARACTER -> JKJavaPrimitiveType.CHAR
        StandardNames.FqNames._boolean.asString(), CommonClassNames.JAVA_LANG_BOOLEAN -> JKJavaPrimitiveType.BOOLEAN
        StandardNames.FqNames._int.asString(), CommonClassNames.JAVA_LANG_INTEGER -> JKJavaPrimitiveType.INT
        StandardNames.FqNames._long.asString(), CommonClassNames.JAVA_LANG_LONG -> JKJavaPrimitiveType.LONG
        StandardNames.FqNames._float.asString(), CommonClassNames.JAVA_LANG_FLOAT -> JKJavaPrimitiveType.FLOAT
        StandardNames.FqNames._double.asString(), CommonClassNames.JAVA_LANG_DOUBLE -> JKJavaPrimitiveType.DOUBLE
        StandardNames.FqNames._byte.asString(), CommonClassNames.JAVA_LANG_BYTE -> JKJavaPrimitiveType.BYTE
        StandardNames.FqNames._short.asString(), CommonClassNames.JAVA_LANG_SHORT -> JKJavaPrimitiveType.SHORT
        else -> null
    }

internal fun JKJavaPrimitiveType.isNumberType() =
    this == JKJavaPrimitiveType.INT ||
            this == JKJavaPrimitiveType.LONG ||
            this == JKJavaPrimitiveType.FLOAT ||
            this == JKJavaPrimitiveType.DOUBLE

internal fun JKJavaPrimitiveType.isBoolean() = jvmPrimitiveType == JvmPrimitiveType.BOOLEAN
internal fun JKJavaPrimitiveType.isChar() = jvmPrimitiveType == JvmPrimitiveType.CHAR
internal fun JKJavaPrimitiveType.isLong() = jvmPrimitiveType == JvmPrimitiveType.LONG
internal fun JKJavaPrimitiveType.isByte(): Boolean = this == JKJavaPrimitiveType.BYTE
internal fun JKJavaPrimitiveType.isShort(): Boolean = this == JKJavaPrimitiveType.SHORT
internal fun JKJavaPrimitiveType.isFloatingPoint(): Boolean =
    this == JKJavaPrimitiveType.FLOAT || this == JKJavaPrimitiveType.DOUBLE

fun JKJavaPrimitiveType.kotlinName() =
    jvmPrimitiveType.javaKeywordName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

val primitiveTypes: List<JvmPrimitiveType> =
    listOf(
        JvmPrimitiveType.BOOLEAN,
        JvmPrimitiveType.CHAR,
        JvmPrimitiveType.BYTE,
        JvmPrimitiveType.SHORT,
        JvmPrimitiveType.INT,
        JvmPrimitiveType.FLOAT,
        JvmPrimitiveType.LONG,
        JvmPrimitiveType.DOUBLE
    )

fun JKType.arrayFqName(): String =
    if (this is JKJavaPrimitiveType)
        PrimitiveType.valueOf(jvmPrimitiveType.name).arrayTypeFqName.asString()
    else StandardNames.FqNames.array.asString()

fun JKClassSymbol.isArrayType(): Boolean =
    fqName in arrayFqNames

private val arrayFqNames = buildList {
    JKJavaPrimitiveType.ALL.mapTo(this) { PrimitiveType.valueOf(it.jvmPrimitiveType.name).arrayTypeFqName.asString() }
    add(StandardNames.FqNames.array.asString())
}

fun JKType.isArrayType() =
    when (this) {
        is JKClassType -> classReference.isArrayType()
        is JKJavaArrayType -> true
        else -> false
    }

fun JKType.isUnit(): Boolean =
    fqName == StandardNames.FqNames.unit.asString()

val JKType.isCollectionType: Boolean
    get() = fqName in collectionFqNames

val JKType.fqName: String?
    get() = safeAs<JKClassType>()?.classReference?.fqName

private val collectionFqNames = setOf(
    StandardNames.FqNames.mutableIterator.asString(),
    StandardNames.FqNames.mutableList.asString(),
    StandardNames.FqNames.mutableCollection.asString(),
    StandardNames.FqNames.mutableSet.asString(),
    StandardNames.FqNames.mutableMap.asString(),
    StandardNames.FqNames.mutableMapEntry.asString(),
    StandardNames.FqNames.mutableListIterator.asString()
)

fun JKType.arrayInnerType(): JKType? =
    when (this) {
        is JKJavaArrayType -> type
        is JKClassType ->
            if (this.classReference.isArrayType()) this.parameters.singleOrNull()
            else null

        else -> null
    }

fun JKMethodSymbol.isAnnotationMethod(): Boolean =
    when (val target = target) {
        is JKJavaAnnotationMethod, is PsiAnnotationMethodImpl -> true
        is ClsMethodImpl -> target.containingClass?.isAnnotationType == true
        else -> false
    }

fun JKClassSymbol.isInterface(): Boolean {
    return when (val target = target) {
        is PsiClass -> target.isInterface
        is KtClass -> target.isInterface()
        is JKClass -> target.classKind == JKClass.ClassKind.INTERFACE
        else -> false
    }
}

fun JKType.isInterface(): Boolean =
    (this as? JKClassType)?.classReference?.isInterface() ?: false

fun JKType.replaceJavaClassWithKotlinClassType(symbolProvider: JKSymbolProvider): JKType =
    applyRecursive { type ->
        if (type is JKClassType && type.classReference.fqName == "java.lang.Class") {
            JKClassType(
                symbolProvider.provideClassSymbol(StandardNames.FqNames.kClass.toSafe()),
                type.parameters.map { it.replaceJavaClassWithKotlinClassType(symbolProvider) },
                NotNull
            )
        } else null
    }

fun JKLiteralExpression.isNull(): Boolean =
    this.type == JKLiteralExpression.LiteralType.NULL

fun JKParameter.determineType(symbolProvider: JKSymbolProvider): JKType =
    if (isVarArgs) {
        val typeParameters =
            if (type.type is JKJavaPrimitiveType) emptyList() else listOf(JKVarianceTypeParameterType(Variance.OUT, type.type))
        JKClassType(symbolProvider.provideClassSymbol(type.type.arrayFqName()), typeParameters, NotNull)
    } else type.type