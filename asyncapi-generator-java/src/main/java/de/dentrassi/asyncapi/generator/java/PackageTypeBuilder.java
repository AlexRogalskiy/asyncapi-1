/*
 * Copyright (C) 2017 Jens Reimann <jreimann@redhat.com>
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

package de.dentrassi.asyncapi.generator.java;

import static de.dentrassi.asyncapi.generator.java.util.JDTHelper.makePublic;
import static de.dentrassi.asyncapi.generator.java.util.JDTHelper.makeStatic;
import static de.dentrassi.asyncapi.generator.java.util.JDTHelper.newText;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Assignment.Operator;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier.ModifierKeyword;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import de.dentrassi.asyncapi.Type;
import de.dentrassi.asyncapi.TypeReference;
import de.dentrassi.asyncapi.generator.java.util.JDTHelper;
import de.dentrassi.asyncapi.generator.java.util.Names;

@SuppressWarnings("unchecked")
public class PackageTypeBuilder implements TypeBuilder {

    private static class ClassTypeBuilder implements TypeBuilder {

        private final AST ast;
        private final CompilationUnit cu;
        private final TypeDeclaration td;
        private final Function<String, Type> typeLookup;

        public ClassTypeBuilder(final AST ast, final CompilationUnit cu, final TypeDeclaration td, final Function<String, Type> typeLookup) {
            this.ast = ast;
            this.cu = cu;
            this.td = td;
            this.typeLookup = typeLookup;
        }

        @Override
        public void createBodyContent(final BiFunction<AST, CompilationUnit, ASTNode> consumer) {
            final ASTNode result = consumer.apply(this.ast, this.cu);
            if (result != null) {
                this.td.bodyDeclarations().add(result);
            }
        }

        @Override
        public void createType(final TypeInformation type, final Consumer<TypeDeclaration> typeCustomizer, final Consumer<TypeBuilder> consumer) {
            final TypeDeclaration td = PackageTypeBuilder.createType(this.ast, this.cu, typeCustomizer, type);
            this.td.bodyDeclarations().add(td);

            consumer.accept(new ClassTypeBuilder(this.ast, this.cu, td, this.typeLookup));
        }

        @Override
        public void createEnum(final TypeInformation type, final Set<String> literals, final BiConsumer<String, EnumConstantDeclaration> constantCustomizer,
                final boolean withOriginalValues) {
            final EnumDeclaration ed = PackageTypeBuilder.createEnum(this.ast, this.cu, type, literals, constantCustomizer, withOriginalValues);
            this.td.bodyDeclarations().add(ed);
            makeStatic(ed);
        }

        @Override
        public void createProperty(final PropertyInformation property) {
            PackageTypeBuilder.createProperty(this.ast, this.td, property, this.typeLookup);
        }

    }

    private final Charset charset;
    private final String packageName;
    private final Path rootPath;
    private final Function<String, Type> typeLookup;

    public PackageTypeBuilder(final Path root, final String packageName, final Charset charset, final Function<String, Type> typeLookup) {
        this.charset = charset;
        this.packageName = packageName;
        this.rootPath = root;
        this.typeLookup = typeLookup;
    }

    public static String asTypeName(final String name) {
        return Names.toCamelCase(name, true);
    }

    private static String asConstantName(final String name) {
        return Names.toUpperUnderscore(name);
    }

    public static String asPropertyName(final String name) {
        return Names.toCamelCase(name, false);
    }

    private static String asMethodPropertyName(final String prefix, final String name) {
        return prefix + Names.toCamelCase(name, true);
    }

    protected void createNew(final String name, final BiConsumer<AST, CompilationUnit> consumer) {
        createCompilationUnit(this.rootPath, this.packageName, name, this.charset, consumer);
    }

    public static void createCompilationUnit(final Path rootPath, final String packageName, final String name, final Charset charset,
            final BiConsumer<AST, CompilationUnit> consumer) {
        final AST ast = AST.newAST(AST.JLS8);

        final CompilationUnit cu = ast.newCompilationUnit();

        final PackageDeclaration pkg = ast.newPackageDeclaration();
        pkg.setName(ast.newName(packageName));
        cu.setPackage(pkg);

        final Path path = rootPath.resolve(packageName.replace(".", File.separator)).resolve(name + ".java");

        consumer.accept(ast, cu);

        try {
            Files.createDirectories(path.getParent());

            try (Writer writer = Files.newBufferedWriter(path, charset)) {
                writer.append(cu.toString());
            }
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public void createType(final TypeInformation type, final boolean iface, final boolean serializable, final Consumer<TypeBuilder> consumer) {
        final Consumer<TypeDeclaration> typeCustomizer = TypeBuilder.asInterface(iface) //
                .andThen(TypeBuilder.superInterfaces(serializable ? Collections.singletonList("java.io.Serializable") : Collections.emptyList()));

        createType(type, typeCustomizer, consumer);
    }

    @Override
    public void createType(final TypeInformation type, final Consumer<TypeDeclaration> typeCustomizer, final Consumer<TypeBuilder> consumer) {
        createNew(type.getName(), (ast, cu) -> {
            final TypeDeclaration td = createType(ast, cu, typeCustomizer, type);
            cu.types().add(td);
            consumer.accept(new ClassTypeBuilder(ast, cu, td, this.typeLookup));
        });
    }

    @Override
    public void createEnum(final TypeInformation type, final Set<String> literals, final BiConsumer<String, EnumConstantDeclaration> constantCustomizer,
            final boolean withOriginalValues) {
        createNew(type.getName(), (ast, cu) -> cu.types().add(createEnum(ast, cu, type, literals, constantCustomizer, withOriginalValues)));
    }

    @Override
    public void createProperty(final PropertyInformation property) {
        throw new IllegalStateException("Unable to create property on package level");
    }

    @Override
    public void createBodyContent(final BiFunction<AST, CompilationUnit, ASTNode> consumer) {
        throw new IllegalStateException("Unable to create body content on package level");
    }

    private static TypeDeclaration createType(final AST ast, final CompilationUnit cu, final Consumer<TypeDeclaration> typeCustomizer, final TypeInformation type) {
        final TypeDeclaration td = ast.newTypeDeclaration();

        makePublic(td);

        if (typeCustomizer != null) {
            typeCustomizer.accept(td);
        }

        addJavadoc(ast, type, td);

        td.setName(ast.newSimpleName(type.getName()));

        return td;
    }

    private static EnumDeclaration createEnum(final AST ast, final CompilationUnit cu, final TypeInformation type, final Set<String> literals,
            final BiConsumer<String, EnumConstantDeclaration> constantCustomizer, final boolean withOriginalValues) {
        final EnumDeclaration ed = ast.newEnumDeclaration();

        addJavadoc(ast, type, ed);

        ed.setName(ast.newSimpleName(type.getName()));
        makePublic(ed);

        for (final String literal : literals) {
            final EnumConstantDeclaration l = ast.newEnumConstantDeclaration();

            l.setName(ast.newSimpleName(asConstantName(literal)));
            if (withOriginalValues) {
                l.arguments().add(JDTHelper.newStringLiteral(ast, literal));
            }

            ed.enumConstants().add(l);

            if (constantCustomizer != null) {
                constantCustomizer.accept(literal, l);
            }
        }

        if (withOriginalValues) {

            // field

            ed.bodyDeclarations().add(JDTHelper.createField(ast, "String", "value", ModifierKeyword.PRIVATE_KEYWORD, ModifierKeyword.FINAL_KEYWORD));

            // constructor

            {
                final MethodDeclaration md = ast.newMethodDeclaration();
                ed.bodyDeclarations().add(md);

                md.setConstructor(true);
                JDTHelper.makePrivate(md);
                md.setName(ast.newSimpleName(type.getName()));

                md.parameters().add(JDTHelper.createParameter(ast, "String", "value", ModifierKeyword.FINAL_KEYWORD));

                final Block body = ast.newBlock();
                md.setBody(body);

                final Assignment as = ast.newAssignment();
                final FieldAccess fa = ast.newFieldAccess();
                fa.setExpression(ast.newThisExpression());
                fa.setName(ast.newSimpleName("value"));
                as.setLeftHandSide(fa);

                as.setRightHandSide(ast.newSimpleName("value"));

                body.statements().add(ast.newExpressionStatement(as));
            }

            // toString

            {
                final MethodDeclaration md = ast.newMethodDeclaration();
                ed.bodyDeclarations().add(md);

                md.setName(ast.newSimpleName("toString"));
                JDTHelper.makePublic(md);
                JDTHelper.addSimpleAnnotation(md, "Override");
                md.setReturnType2(ast.newSimpleType(ast.newSimpleName("String")));

                final Block body = ast.newBlock();
                md.setBody(body);

                final ReturnStatement ret = ast.newReturnStatement();
                body.statements().add(ret);

                final FieldAccess fa = ast.newFieldAccess();
                fa.setExpression(ast.newThisExpression());
                fa.setName(ast.newSimpleName("value"));
                ret.setExpression(fa);
            }

        }

        return ed;
    }

    public static Type lookupType(final TypeReference type, final Function<String, Type> typeLookup) {
        if (type instanceof Type) {
            return (Type) type;
        }

        return typeLookup.apply(type.getName());
    }

    public static void createProperty(final AST ast, final TypeDeclaration td, final PropertyInformation property, final Function<String, Type> typeLookup) {

        final String name = asPropertyName(property.getName());

        final VariableDeclarationFragment fragment = ast.newVariableDeclarationFragment();
        fragment.setName(ast.newSimpleName(name));

        final FieldDeclaration fd = ast.newFieldDeclaration(fragment);
        fd.modifiers().add(ast.newModifier(ModifierKeyword.PRIVATE_KEYWORD));
        fd.setType(createPropertyType(ast, property, typeLookup));

        final Javadoc doc = createJavadoc(ast, property.getSummary(), property.getDescription());
        if (doc != null) {
            fd.setJavadoc(doc);
        }

        td.bodyDeclarations().add(fd);

        // setter

        final MethodDeclaration setter = ast.newMethodDeclaration();
        td.bodyDeclarations().add(setter);
        makePublic(setter);

        setter.setName(ast.newSimpleName(asMethodPropertyName("set", name)));
        final SingleVariableDeclaration arg = ast.newSingleVariableDeclaration();
        arg.setName(ast.newSimpleName(name));
        arg.setType(createPropertyType(ast, property, typeLookup));
        arg.modifiers().add(ast.newModifier(ModifierKeyword.FINAL_KEYWORD));
        setter.parameters().add(arg);

        {
            final Block body = ast.newBlock();

            final FieldAccess fa = ast.newFieldAccess();
            fa.setName(ast.newSimpleName(name));
            fa.setExpression(ast.newThisExpression());

            final Assignment assign = ast.newAssignment();
            assign.setLeftHandSide(fa);
            assign.setOperator(Operator.ASSIGN);
            assign.setRightHandSide(ast.newSimpleName(name));

            final ExpressionStatement expStmt = ast.newExpressionStatement(assign);

            body.statements().add(expStmt);
            setter.setBody(body);
        }

        // getter

        final MethodDeclaration getter = ast.newMethodDeclaration();
        td.bodyDeclarations().add(getter);
        makePublic(getter);

        getter.setName(ast.newSimpleName(asMethodPropertyName("get", name)));
        getter.setReturnType2(createPropertyType(ast, property, typeLookup));

        {
            final Block body = ast.newBlock();
            final ReturnStatement retStmt = ast.newReturnStatement();
            final FieldAccess fa = ast.newFieldAccess();
            fa.setName(ast.newSimpleName(name));
            fa.setExpression(ast.newThisExpression());
            retStmt.setExpression(fa);
            body.statements().add(retStmt);
            getter.setBody(body);
        }

    }

    private static org.eclipse.jdt.core.dom.Type createPropertyType(final AST ast, final PropertyInformation property, final Function<String, Type> typeLookup) {

        switch (property.getTypeName()) {
        case "short":
            return ast.newPrimitiveType(PrimitiveType.SHORT);
        case "int":
            return ast.newPrimitiveType(PrimitiveType.INT);
        case "long":
            return ast.newPrimitiveType(PrimitiveType.LONG);
        case "boolean":
            return ast.newPrimitiveType(PrimitiveType.BOOLEAN);
        case "double":
            return ast.newPrimitiveType(PrimitiveType.DOUBLE);
        case "float":
            return ast.newPrimitiveType(PrimitiveType.FLOAT);
        case "char":
            return ast.newPrimitiveType(PrimitiveType.CHAR);
        case "byte":
            return ast.newPrimitiveType(PrimitiveType.BYTE);
        }

        return ast.newSimpleType(ast.newName(property.getTypeName()));
    }

    private static void addJavadoc(final AST ast, final TypeInformation type, final BodyDeclaration bd) {
        final Javadoc doc = createJavadoc(ast, type.getSummary(), type.getDescription());
        if (doc != null) {
            bd.setJavadoc(doc);
        }
    }

    private static boolean isEmpty(final String text) {
        return text == null || text.isEmpty();
    }

    private static Javadoc createJavadoc(final AST ast, final String title, final String description) {
        if (isEmpty(title) && isEmpty(description)) {
            return null;
        }

        final Javadoc doc = ast.newJavadoc();

        if (title != null) {
            final TagElement tag = ast.newTagElement();
            tag.fragments().add(newText(ast, title));
            doc.tags().add(tag);
        }

        if (description != null) {
            final TagElement tag = ast.newTagElement();
            tag.fragments().add(newText(ast, "<p>" + description + "</p>"));
            doc.tags().add(tag);
        }

        return doc;
    }

}
