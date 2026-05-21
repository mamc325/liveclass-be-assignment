package com.example.enrollment_system.common.auth;

import com.example.enrollment_system.common.error.ErrorCode;
import com.example.enrollment_system.user.User;
import com.example.enrollment_system.user.UserRepository;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String HEADER = "X-USER-ID";

    private final UserRepository userRepository;

    public CurrentUserArgumentResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
            && AuthUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) throws Exception {
        String header = webRequest.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            // GlobalExceptionHandler가 UNAUTHENTICATED로 매핑
            throw new MissingRequestHeaderException(HEADER, parameter);
        }

        long userId;
        try {
            userId = Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            throw ErrorCode.UNAUTHENTICATED.with("유효하지 않은 X-USER-ID 형식입니다.");
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() -> ErrorCode.UNAUTHENTICATED.with(
                "존재하지 않는 사용자입니다. (id=" + userId + ")"));

        return new AuthUser(user.getId(), user.getRole());
    }
}
