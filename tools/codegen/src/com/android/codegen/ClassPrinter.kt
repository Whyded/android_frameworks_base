package com.android.codegen

import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.type.ClassOrInterfaceType

/**
 * [ClassInfo] + utilities for printing out new class code with proper indentation and imports
 */
class ClassPrinter(
    source: List<String>,
    private val stringBuilder: StringBuilder,
    var cliArgs: Array<String>
) : ClassInfo(source) {

    val GENERATED_MEMBER_HEADER by lazy { "@$GeneratedMember" }

    // Imports
    val NonNull by lazy { classRef("android.annotation.NonNull") }
    val NonEmpty by lazy { classRef("android.annotation.NonEmpty") }
    val Nullable by lazy { classRef("android.annotation.Nullable") }
    val TextUtils by lazy { classRef("android.text.TextUtils") }
    val LinkedHashMap by lazy { classRef("java.util.LinkedHashMap") }
    val Collections by lazy { classRef("java.util.Collections") }
    val Preconditions by lazy { classRef("com.android.internal.util.Preconditions") }
    val ArrayList by lazy { classRef("java.util.ArrayList") }
    val DataClass by lazy { classRef("com.android.internal.util.DataClass") }
    val DataClassEnum by lazy { classRef("com.android.internal.util.DataClass.Enum") }
    val ParcelWith by lazy { classRef("com.android.internal.util.DataClass.ParcelWith") }
    val PluralOf by lazy { classRef("com.android.internal.util.DataClass.PluralOf") }
    val Each by lazy { classRef("com.android.internal.util.DataClass.Each") }
    val DataClassGenerated by lazy { classRef("com.android.internal.util.DataClass.Generated") }
    val DataClassSuppressConstDefs by lazy { classRef("com.android.internal.util.DataClass.SuppressConstDefsGeneration") }
    val DataClassSuppress by lazy { classRef("com.android.internal.util.DataClass.Suppress") }
    val GeneratedMember by lazy { classRef("com.android.internal.util.DataClass.Generated.Member") }
    val Parcelling by lazy { classRef("com.android.internal.util.Parcelling") }
    val Parcelable by lazy { classRef("android.os.Parcelable") }
    val UnsupportedAppUsage by lazy { classRef("android.annotation.UnsupportedAppUsage") }

    init {
        val fieldsWithMissingNullablity = fields.filter { field ->
            !field.isPrimitive
                    && Modifier.TRANSIENT !in field.fieldAst.modifiers
                    && "@$Nullable" !in field.annotations
                    && "@$NonNull" !in field.annotations
        }
        if (fieldsWithMissingNullablity.isNotEmpty()) {
            abort("Non-primitive fields must have @$Nullable or @$NonNull annotation.\n" +
                    "Missing nullability annotations on: "
                    + fieldsWithMissingNullablity.joinToString(", ") { it.name })
        }

        if (!classAst.isFinal &&
                classAst.extendedTypes.any { it.nameAsString == Parcelable }) {
            abort("Parcelable classes must be final")
        }
    }

    /**
     * Optionally shortens a class reference if there's a corresponding import present
     */
    fun classRef(fullName: String): String {
        if (cliArgs.contains(FLAG_NO_FULL_QUALIFIERS)) {
            return fullName.split(".").dropWhile { it[0].isLowerCase() }.joinToString(".")
        }

        val pkg = fullName.substringBeforeLast(".")
        val simpleName = fullName.substringAfterLast(".")
        if (fileAst.imports.any { imprt ->
                    imprt.nameAsString == fullName
                            || (imprt.isAsterisk && imprt.nameAsString == pkg)
                }) {
            return simpleName
        } else {
            val outerClass = pkg.substringAfterLast(".", "")
            if (outerClass.firstOrNull()?.isUpperCase() == true) {
                return classRef(pkg) + "." + simpleName
            }
        }
        return fullName
    }

    /** @see classRef */
    inline fun <reified T : Any> classRef(): String {
        return classRef(T::class.java.name)
    }

    /** @see classRef */
    fun memberRef(fullName: String): String {
        val className = fullName.substringBeforeLast(".")
        val methodName = fullName.substringAfterLast(".")
        return if (fileAst.imports.any {
                    it.isStatic
                            && (it.nameAsString == fullName
                            || (it.isAsterisk && it.nameAsString == className))
                }) {
            className.substringAfterLast(".") + "." + methodName
        } else {
            classRef(className) + "." + methodName
        }
    }

    val dataClassAnnotationFeatures = classAst.annotations
            .find { it.nameAsString == DataClass }
            ?.let { it as? NormalAnnotationExpr }
            ?.pairs
            ?.map { pair -> pair.nameAsString to (pair.value as BooleanLiteralExpr).value }
            ?.toMap()
            ?: emptyMap()

    val internalAnnotations = setOf(ParcelWith, DataClassEnum, PluralOf, UnsupportedAppUsage,
            DataClassSuppressConstDefs)
    val knownNonValidationAnnotations = internalAnnotations + Each + Nullable

    /**
     * @return whether the given feature is enabled
     */
    operator fun FeatureFlag.invoke(): Boolean {
        if (cliArgs.contains("--no-$kebabCase")) return false
        if (cliArgs.contains("--$kebabCase")) return true

        val annotationKey = "gen$upperCamelCase"
        if (dataClassAnnotationFeatures.containsKey(annotationKey)) {
            return dataClassAnnotationFeatures[annotationKey]!!
        }

        if (cliArgs.contains("--all")) return true
        if (hidden) return true

        return when (this) {
            FeatureFlag.SETTERS ->
                !FeatureFlag.CONSTRUCTOR() && !FeatureFlag.BUILDER() && fields.any { !it.isFinal }
            FeatureFlag.BUILDER -> cliArgs.contains(FLAG_BUILDER_PROTECTED_SETTERS)
                    || fields.any { it.hasDefault }
                    || onByDefault
            FeatureFlag.CONSTRUCTOR -> !FeatureFlag.BUILDER()
            FeatureFlag.PARCELABLE -> "Parcelable" in superInterfaces
            FeatureFlag.AIDL -> FeatureFlag.PARCELABLE()
            FeatureFlag.IMPLICIT_NONNULL -> fields.any { it.isNullable }
                    && fields.none { "@$NonNull" in it.annotations }
            else -> onByDefault
        }
    }

    val FeatureFlag.hidden
        get(): Boolean = when {
            cliArgs.contains("--hidden-$kebabCase") -> true
            this == FeatureFlag.BUILD_UPON -> FeatureFlag.BUILDER.hidden
            else -> false
        }

    var currentIndent = INDENT_SINGLE
        private set

    fun pushIndent() {
        currentIndent += INDENT_SINGLE
    }

    fun popIndent() {
        currentIndent = currentIndent.substring(0, currentIndent.length - INDENT_SINGLE.length)
    }

    fun backspace() = stringBuilder.setLength(stringBuilder.length - 1)
    val lastChar get() = stringBuilder[stringBuilder.length - 1]

    private fun appendRaw(s: String) {
        stringBuilder.append(s)
    }

    fun append(s: String) {
        if (s.isBlank() && s != "\n") {
            appendRaw(s)
        } else {
            appendRaw(s.lines().map { line ->
                if (line.startsWith(" *")) line else line.trimStart()
            }.joinToString("\n$currentIndent"))
        }
    }

    fun appendSameLine(s: String) {
        while (lastChar.isWhitespace() || lastChar.isNewline()) {
            backspace()
        }
        appendRaw(s)
    }

    fun rmEmptyLine() {
        while (lastChar.isWhitespaceNonNewline()) backspace()
        if (lastChar.isNewline()) backspace()
    }

    /**
     * Syntactic sugar for:
     * ```
     * +"code()";
     * ```
     * to append the given string plus a newline
     */
    operator fun String.unaryPlus() = append("$this\n")

    /**
     * Syntactic sugar for:
     * ```
     * !"code()";
     * ```
     * to append the given string without a newline
     */
    operator fun String.not() = append(this)

    /**
     * Syntactic sugar for:
     * ```
     * ... {
     *     ...
     * }+";"
     * ```
     * to append a ';' on same line after a block, and a newline afterwards
     */
    operator fun Unit.plus(s: String) {
        appendSameLine(s)
        +""
    }

    /**
     * A multi-purpose syntactic sugar for appending the given string plus anything generated in
     * the given [block], the latter with the appropriate deeper indent,
     * and resetting the indent back to original at the end
     *
     * Usage examples:
     *
     * ```
     * "if (...)" {
     *     ...
     * }
     * ```
     * to append a corresponding if block appropriate indentation
     *
     * ```
     * "void foo(...)" {
     *      ...
     * }
     * ```
     * similar to the previous one, plus an extra empty line after the function body
     *
     * ```
     * "void foo(" {
     *      <args code>
     * }
     * ```
     * to use proper indentation for args code and close the bracket on same line at end
     *
     * ```
     * "..." {
     *     ...
     * }
     * to use the correct indentation for inner code, resetting it at the end
     */
    inline operator fun String.invoke(block: ClassPrinter.() -> Unit) {
        if (this == " {") {
            appendSameLine(this)
        } else {
            append(this)
        }
        when {
            endsWith("(") -> {
                indentedBy(2, block)
                appendSameLine(")")
            }
            endsWith("{") || endsWith(")") -> {
                if (!endsWith("{")) appendSameLine(" {")
                indentedBy(1, block)
                +"}"
                if ((endsWith(") {") || endsWith(")") || this == " {")
                        && !startsWith("synchronized")
                        && !startsWith("switch")
                        && !startsWith("if ")
                        && !contains(" else ")
                        && !contains("new ")
                        && !contains("return ")) {
                    +"" // extra line after function definitions
                }
            }
            else -> indentedBy(2, block)
        }
    }

    inline fun indentedBy(level: Int, block: ClassPrinter.() -> Unit) {
        append("\n")
        level times {
            append(INDENT_SINGLE)
            pushIndent()
        }
        block()
        level times { popIndent() }
        rmEmptyLine()
        +""
    }

    inline fun Iterable<FieldInfo>.forEachTrimmingTrailingComma(b: FieldInfo.() -> Unit) {
        forEachApply {
            b()
            if (isLast) {
                while (lastChar == ' ' || lastChar == '\n') backspace()
                if (lastChar == ',') backspace()
            }
        }
    }

    inline operator fun <R> invoke(f: ClassPrinter.() -> R): R = run(f)

    var BuilderClass = CANONICAL_BUILDER_CLASS
    var BuilderType = BuilderClass + genericArgs
    val customBaseBuilderAst: ClassOrInterfaceDeclaration? by lazy {
        nestedClasses.find { it.nameAsString == BASE_BUILDER_CLASS }
    }

    val suppressedMembers by lazy {
        getSuppressedMembers(classAst)
    }
    val builderSuppressedMembers by lazy {
        getSuppressedMembers(customBaseBuilderAst)
    }

    private fun getSuppressedMembers(clazz: ClassOrInterfaceDeclaration?): List<String> {
        return clazz
                ?.annotations
                ?.find { it.nameAsString == DataClassSuppress }
                ?.as_<SingleMemberAnnotationExpr>()
                ?.memberValue
                ?.run {
                    when (this) {
                        is ArrayInitializerExpr -> values.map { it.asLiteralStringValueExpr().value }
                        is StringLiteralExpr -> listOf(value)
                        else -> abort("Can't parse annotation arg: $this")
                    }
                }
                ?: emptyList()
    }

    fun isMethodGenerationSuppressed(name: String, vararg argTypes: String): Boolean {
        return name in suppressedMembers || hasMethod(name, *argTypes)
    }

    fun hasMethod(name: String, vararg argTypes: String): Boolean {
        return classAst.methods.any {
            it.name.asString() == name &&
                    it.parameters.map { it.type.asString() } == argTypes.toList()
        }
    }

    val lazyTransientFields = classAst.fields
            .filter { it.isTransient && !it.isStatic }
            .mapIndexed { i, node -> FieldInfo(index = i, fieldAst = node, classInfo = this) }
            .filter { hasMethod("lazyInit${it.NameUpperCamel}") }

    init {
        val builderFactoryOverride = classAst.methods.find {
            it.isStatic && it.nameAsString == "builder"
        }
        if (builderFactoryOverride != null) {
            BuilderClass = (builderFactoryOverride.type as ClassOrInterfaceType).nameAsString
            BuilderType = builderFactoryOverride.type.asString()
        } else {
            val builderExtension = (fileAst.types
                    + classAst.childNodes.filterIsInstance(TypeDeclaration::class.java)).find {
                it.nameAsString == CANONICAL_BUILDER_CLASS
            }
            if (builderExtension != null) {
                BuilderClass = BASE_BUILDER_CLASS
                val tp = (builderExtension as ClassOrInterfaceDeclaration).typeParameters
                BuilderType = if (tp.isEmpty()) BuilderClass
                else "$BuilderClass<${tp.map { it.nameAsString }.joinToString(", ")}>"
            }
        }
    }
}