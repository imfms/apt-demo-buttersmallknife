package ms.imf.buttersmallknife.compiler;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.WildcardTypeName;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

import ms.imf.buttersmallknife.annotation.Bind;

@AutoService(Processor.class)
public class ButterSmallKnifeAnnotationProcessor extends AbstractProcessor {

    public static final String TYPE_ANDROID_VIEW = "android.view.View";
    public static final String PACKAGE_NAME_BUTTERSMALLKNIFE = "ms.imf.buttersmallknife";

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Collections.singleton(Bind.class.getCanonicalName());
    }

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnvironment) {

        Map<DeclaredType, List<VariableElement>> bindTypesFieldsMap = getBindTypesFieldsMap(roundEnvironment);
        if (bindTypesFieldsMap.isEmpty()) {
            return true;
        }

        // ViewBinder interface
        TypeVariableName typeVariableTarget = TypeVariableName.get("Target");
        ParameterSpec paramTarget = ParameterSpec.builder(typeVariableTarget, "target").build();
        ParameterSpec paramView = ParameterSpec.builder(TypeName.get(processingEnv.getElementUtils().getTypeElement(TYPE_ANDROID_VIEW).asType()), "view").build();
        MethodSpec viewBinderBindMethod = MethodSpec.methodBuilder("bind")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter(paramTarget)
                .addParameter(paramView)
                .build();

        JavaFile viewBinderInterface = JavaFile.builder(
                PACKAGE_NAME_BUTTERSMALLKNIFE,
                TypeSpec.interfaceBuilder("ViewBinder")
                        .addModifiers(Modifier.PUBLIC)
                        .addTypeVariable(typeVariableTarget)
                        .addMethod(viewBinderBindMethod)
                        .build()
        ).build();

        CodeBlock.Builder bindersStoreCodeBlock = CodeBlock.builder();

        final List<JavaFile> viewBinders = new ArrayList<>();

        for (Map.Entry<DeclaredType, List<VariableElement>> entry : bindTypesFieldsMap.entrySet()) {
            DeclaredType type = entry.getKey();
            List<VariableElement> fields = entry.getValue();

            JavaFile javaFile = generateJavaFile(viewBinderInterface, viewBinderBindMethod, paramTarget, paramView, type, fields);
            viewBinders.add(javaFile);

            bindersStoreCodeBlock.addStatement(
                    "binders.put($T.class, new $N())",
                    type,
                    javaFile.typeSpec
            );
        }

        JavaFile butterSmallKnifeHelperType = JavaFile
                .builder(
                        PACKAGE_NAME_BUTTERSMALLKNIFE,
                        getHelperTypeSpec(viewBinderInterface, bindersStoreCodeBlock, "ButterSmallKnife")
                )
                .build();

        try {
            viewBinderInterface.writeTo(processingEnv.getFiler());
            for (JavaFile viewBinder : viewBinders) {
                viewBinder.writeTo(processingEnv.getFiler());
            }
            butterSmallKnifeHelperType.writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            throw new RuntimeException("unexpected exception '" + e.getMessage() + "'", e);
        }

        return true;
    }

    private TypeSpec getHelperTypeSpec(JavaFile viewBinderInterface, CodeBlock.Builder bindersStoreCodeBlock, String typeName) {
        return TypeSpec
                .classBuilder(typeName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addStaticBlock(bindersStoreCodeBlock.build())
                .addField(
                        FieldSpec
                                .builder(
                                        ParameterizedTypeName
                                                .get(
                                                        ClassName.get(Map.class),
                                                        ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.get(processingEnv.getTypeUtils().getWildcardType(null, null))),
                                                        ParameterizedTypeName.get(ClassName.get(viewBinderInterface.packageName, viewBinderInterface.typeSpec.name), WildcardTypeName.get(processingEnv.getTypeUtils().getWildcardType(null, null)))
                                                ),
                                        "binders",
                                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL
                                )
                                .initializer("new $T<>()", HashMap.class)
                                .build()
                )
                .addMethod(
                        MethodSpec.methodBuilder("bind")
                                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                                .addParameter(Object.class, "target")
                                .addParameter(TypeName.get(processingEnv.getElementUtils().getTypeElement(TYPE_ANDROID_VIEW).asType()), "view")
                                .beginControlFlow("if (target == null)")
                                .addStatement("throw new $T($S)", NullPointerException.class, "target can't be null")
                                .endControlFlow()
                                .beginControlFlow("if (view == null)")
                                .addStatement("throw new $T($S)", NullPointerException.class, "view can't be null")
                                .endControlFlow()
                                .addStatement("$N viewBinder = binders.get(target.getClass())", viewBinderInterface.typeSpec)
                                .beginControlFlow("if (viewBinder == null)")
                                .addStatement("throw new $T($S + target.getClass().getCanonicalName())", IllegalStateException.class, "can't find binder in ")
                                .endControlFlow()
                                .addStatement("viewBinder.bind(target, view)")
                                .build()
                )
                .build();
    }

    private JavaFile generateJavaFile(JavaFile viewBinderInterface, MethodSpec viewBinderBindMethod, ParameterSpec paramTarget, ParameterSpec paramView, DeclaredType type, List<VariableElement> fields) {

        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(viewBinderBindMethod.name)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeName.get(type), paramTarget.name)
                .addParameter(paramView);

        for (VariableElement field : fields) {

            Bind bindAnnotation = field.getAnnotation(Bind.class);

            String typeFullName = ((TypeElement) type.asElement()).getQualifiedName().toString();
            String fieldFullName = ((TypeElement) type.asElement()).getQualifiedName().toString() + '.' + field.getSimpleName();

            methodBuilder
                    .beginControlFlow("try")
                    .addStatement("target.$L = view.findViewById($L)", field.getSimpleName(), bindAnnotation.value())
                    .endControlFlow()
                    .beginControlFlow("catch ($T e)", ClassCastException.class)
                    .addStatement("throw new $T($S + e.getMessage(), e)",
                            IllegalStateException.class,
                            String.format(
                                    "%s's type is %s, but happened ClassCastException from resource id '0x%x': ",
                                    fieldFullName,
                                    field.asType(),
                                    bindAnnotation.value()
                            )
                    )
                    .endControlFlow()
                    .beginControlFlow("if (target.$L == null)", field.getSimpleName())
                    .addStatement(
                            "throw new $T($S)",
                            IllegalStateException.class,
                            String.format(
                                    "in %s can't find view from resource id '0x%x' for field '%s'",
                                    typeFullName,
                                    bindAnnotation.value(),
                                    field.getSimpleName()
                            )
                    )
                    .endControlFlow();
        }

        TypeSpec typeSpec = TypeSpec
                .classBuilder(type.asElement().getSimpleName() + "_ViewBinder")
                .addSuperinterface(ParameterizedTypeName.get(
                        ClassName.get(viewBinderInterface.packageName, viewBinderInterface.typeSpec.name),
                        TypeName.get(type)
                ))
                .addMethod(
                        methodBuilder.build()
                )
                .build();
        String packageName = processingEnv.getElementUtils().getPackageOf(type.asElement()).getQualifiedName().toString();

        return JavaFile
                .builder(
                        packageName,
                        typeSpec
                )
                .build();
    }

    private Map<DeclaredType, List<VariableElement>> getBindTypesFieldsMap(RoundEnvironment roundEnvironment) {
        Map<DeclaredType, List<VariableElement>> typesFieldsMap = new HashMap<>();

        for (VariableElement variableElement : ElementFilter.fieldsIn(roundEnvironment.getElementsAnnotatedWith(Bind.class))) {
            boolean isView = processingEnv.getTypeUtils().isSubtype(
                    variableElement.asType(),
                    processingEnv.getElementUtils().getTypeElement(TYPE_ANDROID_VIEW).asType()
            );

            if (!isView) {
                printErrorMessage(
                        variableElement,
                        "type must be " + TYPE_ANDROID_VIEW,
                        variableElement.asType().toString()
                );
            }

            if (variableElement.getModifiers().contains(Modifier.PRIVATE)) {
                printErrorMessage(variableElement, "permission modifier must more than private", "private");
            }

            if (variableElement.getModifiers().contains(Modifier.STATIC)) {
                printErrorMessage(variableElement, "can't exist static modifier", "static");
            }

            if (variableElement.getConstantValue() != null) {
                printErrorMessage(variableElement, variableElement.getConstantValue().toString(), variableElement.getConstantValue().toString());
                printErrorMessage(variableElement, "can't exist default value", variableElement.getConstantValue().toString());
            }

            DeclaredType declaredType = (DeclaredType) variableElement.getEnclosingElement().asType();
            List<VariableElement> fields = typesFieldsMap.get(declaredType);
            if (fields == null) {
                typesFieldsMap.put(declaredType, fields = new ArrayList<>());
            }

            fields.add(variableElement);
        }
        return typesFieldsMap;
    }

    private void printErrorMessage(VariableElement variableElement, String errorTip, String errorReason) {
        String errorMessage = String.format(
                "@%s annotated field %s, but found %s in %s.%s.%s",
                Bind.class.getSimpleName(),
                errorTip,
                errorReason,
                processingEnv.getElementUtils().getPackageOf(variableElement).getQualifiedName(),
                variableElement.getEnclosingElement().getSimpleName(),
                variableElement.getSimpleName()
        );
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, errorMessage);
        throw new RuntimeException(errorMessage);
    }

}
