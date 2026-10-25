package com.ledgerline.projection.security;

import com.ledgerline.projection.api.*;
import com.ledgerline.projection.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.view.xslt.XsltViewResolver;
import static org.junit.jupiter.api.Assertions.*;

@WebMvcTest(controllers = {ProjectionController.class}, properties = "projection.security.jwt-secret=test-only-secret-at-least-32-bytes-long")
class ViewRenderingBoundaryTest {
    @Autowired ApplicationContext context;
    @Autowired RequestMappingHandlerMapping mappings;
    @MockitoBean ProjectionService projectionService;
    @MockitoBean com.ledgerline.projection.security.AccountClaimAuthorizer authorizer;

    private static void requireResponseBody(Class<?> type, java.lang.reflect.Method method) {
        String returnType = method.getGenericReturnType().getTypeName();
        assertFalse(returnType.contains("SseEmitter") || returnType.contains("ResponseBodyEmitter")
            || returnType.contains("FragmentsRendering"), "SSE/view fragments invalidate applicability decision");
        assertFalse(org.springframework.web.servlet.ModelAndView.class.isAssignableFrom(method.getReturnType())
            || org.springframework.web.servlet.View.class.isAssignableFrom(method.getReturnType()),
            "Explicit view return type invalidates applicability decision");
        assertTrue(AnnotatedElementUtils.hasAnnotation(type, ResponseBody.class)
            || AnnotatedElementUtils.hasAnnotation(method, ResponseBody.class),
            "CVE-2026-47884 applicability must be reviewed: view-rendering handler " + type.getName());
    }

    @Test void applicationHandlersNeverRenderImplicitViewsAndNoXsltResolverExists() {
        assertTrue(context.getBeansOfType(org.springframework.web.servlet.view.xslt.XsltView.class).isEmpty());
        assertTrue(context.getBeansOfType(XsltViewResolver.class).isEmpty(), "XSLT views invalidate applicability decision");
        mappings.getHandlerMethods().values().stream()
            .filter(handler -> handler.getBeanType().getName().startsWith("com.ledgerline."))
            .forEach(handler -> requireResponseBody(handler.getBeanType(), handler.getMethod()));
        assertFalse(mappings.getHandlerMethods().isEmpty());
    }

    @org.springframework.stereotype.Controller
    static class UnsafeViewFixture {
        @RequestMapping("/**") public String view() { return "untrusted-view"; }
    }
    @org.springframework.web.bind.annotation.RestController
    static class UnsafeStreamFixture {
        @RequestMapping(produces="text/event-stream")
        public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream() { return null; }
    }
    @Test void negativeControlRejectsEventStreams() throws Exception {
        assertThrows(AssertionError.class, () -> requireResponseBody(UnsafeStreamFixture.class,
            UnsafeStreamFixture.class.getMethod("stream")));
    }
    @Test void negativeControlRejectsViewRenderingHandler() throws Exception {
        assertThrows(AssertionError.class, () -> requireResponseBody(UnsafeViewFixture.class,
            UnsafeViewFixture.class.getMethod("view")));
    }
}
