package ms.imf.buttersmallknife.compiler;

import com.google.auto.service.AutoService;

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
                        variableElement.getEnclosingElement().getSimpleName().toString()
                );
                return false;
            }

            if (variableElement.getModifiers().contains(Modifier.PRIVATE)) {
                printErrorMessage(variableElement, "permission modifier must more than private", "private");
                return false;
            }

            if (variableElement.getModifiers().contains(Modifier.STATIC)) {
                printErrorMessage(variableElement, "can't exist static modifier", "static");
                return false;
            }

            DeclaredType declaredType = (DeclaredType) variableElement.getEnclosingElement().asType();
            List<VariableElement> fields = typesFieldsMap.get(declaredType);
            if (fields == null) {
                typesFieldsMap.put(declaredType, fields = new ArrayList<>());
            }

            fields.add(variableElement);
        }

        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, typesFieldsMap.toString());

        return true;
    }

    private void printErrorMessage(VariableElement variableElement, String errorTip, String errorReason) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format(
                "@%s annotated field %s, but found %s in %s.%s.%s",
                Bind.class.getSimpleName(),
                errorTip,
                errorReason,
                processingEnv.getElementUtils().getPackageOf(variableElement).getQualifiedName(),
                variableElement.getEnclosingElement().getSimpleName(),
                variableElement.getSimpleName()
        ));
    }

}
