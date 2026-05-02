package com.abioscase.live.livedata.web.filter;

import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.NonNull;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Applies sparse field selection to any JSON response when ?fields=f1,f2 is present.
 * DTOs opt in by annotating with @JsonFilter(FieldSelectionAdvice.FILTER_NAME).
 * Unknown field names are silently ignored; omitting ?fields returns all fields.
 */
@ControllerAdvice
public class FieldSelectionAdvice implements ResponseBodyAdvice<Object> {

    public static final String FILTER_NAME = "itemFields";

    @Override
    public boolean supports(@NonNull MethodParameter returnType,
                            @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        return AbstractJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, @NonNull MethodParameter returnType,
                                  @NonNull MediaType selectedContentType,
                                  @NonNull Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  @NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response) {

        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return body;
        }
        String raw = servletRequest.getServletRequest().getParameter("fields");
        if (raw == null || raw.isBlank()) {
            return body;
        }
        Set<String> fields = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        if (fields.isEmpty()) {
            return body;
        }

        MappingJacksonValue wrapper = new MappingJacksonValue(body);
        wrapper.setFilters(new SimpleFilterProvider()
                .setDefaultFilter(SimpleBeanPropertyFilter.serializeAll())
                .addFilter(FILTER_NAME, SimpleBeanPropertyFilter.filterOutAllExcept(fields)));
        return wrapper;
    }
}
