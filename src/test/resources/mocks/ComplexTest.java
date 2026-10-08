package org.springframework.security.web.method.annotation;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * This is a Javadoc comment for the class.
 * It tests class-level comments.
 */
@SuppressWarnings("rawtypes")
public final class AuthenticationPrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    /**
     * This is a Javadoc comment for a constant.
     */
    public static final String DEFAULT_PRINCIPAL_ATTRIBUTE = "principal";

    private boolean errorOnInvalidType = false;

    /**
     * This is a Javadoc comment for the resolveArgument method.
     * It tests method-level comments.
     *
     * @param parameter    the method parameter
     * @param mavContainer the mav container
     * @return null
     */
    @Override
    public Object resolveArgument(
            final MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            @SuppressWarnings("unused") WebDataBinderFactory binderFactory) { // Annotation on parameter

        // This is a single line comment inside a method
        // It tests in-method comments.
        int x = 10;
        return null;
    }

    /**
     * This is a Javadoc comment for supportsParameter.
     */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return true;
    }

    /**
     * Dummy method with a comment.
     *
     * @param beanResolver the bean resolver
     */
    public void setBeanResolver(Object beanResolver) {
        // Another single line comment
        int y = 20;
    }

    public void setSecurityContextHolderStrategy(Object strategy) {
        // dummy
    }

    public void setTemplateDefaults(Object templateDefaults) {
        // dummy
    }

    // comment
    private Object findMethodAnnotation(MethodParameter parameter) {
        return null;
    }
}
