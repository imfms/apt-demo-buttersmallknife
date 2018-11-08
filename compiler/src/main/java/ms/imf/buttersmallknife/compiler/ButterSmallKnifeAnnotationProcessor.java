package ms.imf.buttersmallknife.compiler;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.lang.reflect.Constructor;
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

        Map<DeclaredType, List<VariableElement>> typesFieldsMap = getBindTypesFieldsMap(roundEnvironment);

        CodeBlock.Builder bindersStoreCodeBlock = CodeBlock.builder().beginControlFlow("try");

        for (Map.Entry<DeclaredType, List<VariableElement>> entry : typesFieldsMap.entrySet()) {
            DeclaredType type = entry.getKey();
            List<VariableElement> fields = entry.getValue();

            JavaFile javaFile = generateJavaFile(type, fields);
            try {
                javaFile.writeTo(processingEnv.getFiler());
            } catch (IOException e) {
                throw new RuntimeException("unexpected exception '" + e.getMessage() + "'", e);
            }

            bindersStoreCodeBlock.addStatement(
                    "binders.put($T.class, $N.class.getConstructor($T.class, $T.class))",
                    type,
                    javaFile.typeSpec,
                    type,
                    TypeName.get(processingEnv.getElementUtils().getTypeElement(TYPE_ANDROID_VIEW).asType())
            );
        }

        bindersStoreCodeBlock.endControlFlow()
                .beginControlFlow("catch ($T e)", NoSuchMethodException.class)
                .addStatement("throw new $T($S, e)", IllegalStateException.class, "will not run to here")
                .endControlFlow();

        try {
            JavaFile
                    .builder(
                            "ms.imf.buttersmallknife",
                            getHelperTypeSpec(bindersStoreCodeBlock, "ButterSmallKnife")
                    )
                    .build()
                    .writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            e.printStackTrace();
        }

        return true;
    }

    private TypeSpec getHelperTypeSpec(CodeBlock.Builder bindersStoreCodeBlock, String typeName) {

        // Map<Class<?>, Constructor<?>>
        DeclaredType mapClassWConstructorWType = processingEnv.getTypeUtils().getDeclaredType(
                processingEnv.getElementUtils().getTypeElement(Map.class.getCanonicalName()),
                processingEnv.getTypeUtils().getDeclaredType(processingEnv.getElementUtils().getTypeElement(Class.class.getCanonicalName()), processingEnv.getTypeUtils().getWildcardType(null, null)),
                processingEnv.getTypeUtils().getDeclaredType(processingEnv.getElementUtils().getTypeElement(Constructor.class.getCanonicalName()), processingEnv.getTypeUtils().getWildcardType(null, null))
        );

        return TypeSpec
                .classBuilder(typeName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addStaticBlock(bindersStoreCodeBlock.build())
                .addField(
                        FieldSpec.builder(TypeName.get(mapClassWConstructorWType), "binders", Modifier.PRIVATE, Modifier.STATIC)
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
                                .addStatement("$T<?> constructor = binders.get(target.getClass())", Constructor.class)
                                .beginControlFlow("if (constructor == null)")
                                .addStatement("throw new $T($S + target.getClass().getCanonicalName())", IllegalStateException.class, "can't find binder in ")
                                .endControlFlow()
                                .beginControlFlow("try")
                                .addStatement("constructor.newInstance(target, view)")
                                .endControlFlow()
                                .beginControlFlow("catch ($T e)", Exception.class)
                                .addStatement("throw new $T($S + constructor.getDeclaringClass().getCanonicalName(), e)", IllegalStateException.class, "can't invoke binder constructor ")
                                .endControlFlow()
                                .build()
                )
                .build();
    }

    private JavaFile generateJavaFile(DeclaredType type, List<VariableElement> fields) {

        MethodSpec.Builder methodBuilder = MethodSpec.constructorBuilder()
                .addAnnotation(ClassName.get(processingEnv.getElementUtils().getTypeElement("android.support.annotation.Keep")))
                .addParameter(TypeName.get(type), "target")
                .addParameter(TypeName.get(processingEnv.getElementUtils().getTypeElement(TYPE_ANDROID_VIEW).asType()), "view");

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
                .classBuilder(type.asElement().getSimpleName() + "_Bind")
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
