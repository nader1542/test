package com.rcibs.rdv.api.business.corp.schema;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scanne les interfaces/classes "*Mapper" du projet et deduit, pour chaque methode, les couples
 * (classe du modele -> classes DTO) a partir du type de retour et des parametres.
 */
final class MapperScanner {

    private MapperScanner() {
    }

    static Map<Class<?>, Set<Class<?>>> discoverMapperPairs() {
        Map<Class<?>, Set<Class<?>>> result = new LinkedHashMap<>();

        // Scanner classpath qui accepte aussi les interfaces (les mappers MapStruct en sont)
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return beanDefinition.getMetadata().isIndependent();
                    }
                };
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*Mapper$")));

        ClassLoader classLoader = MapperScanner.class.getClassLoader();
        for (BeanDefinition definition : scanner.findCandidateComponents(ModelReflection.BASE_PACKAGE)) {
            Class<?> mapper;
            try {
                mapper = ClassUtils.forName(definition.getBeanClassName(), classLoader);
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            for (Method method : mapper.getMethods()) {
                collectPairs(method, result);
            }
        }
        return result;
    }

    /** Relie chaque classe du modele vue dans la methode aux DTO vus dans la meme methode. */
    private static void collectPairs(Method method, Map<Class<?>, Set<Class<?>>> result) {
        // Type de retour + types des parametres de la methode
        List<Type> types = new ArrayList<>();
        types.add(method.getGenericReturnType());
        types.addAll(Arrays.asList(method.getGenericParameterTypes()));

        Set<Class<?>> models = new LinkedHashSet<>();
        Set<Class<?>> dtos = new LinkedHashSet<>();
        for (Type type : types) {
            Class<?> c = ModelReflection.unwrap(type);
            if (ModelReflection.isModel(c)) {
                models.add(c);
            } else if (ModelReflection.isDto(c)) {
                dtos.add(c);
            }
        }
        for (Class<?> model : models) {
            result.computeIfAbsent(model, k -> new LinkedHashSet<>()).addAll(dtos);
        }
    }
}
