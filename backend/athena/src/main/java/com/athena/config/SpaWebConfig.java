package com.athena.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the built React app (copied into classpath:/static by the Docker
 * build) from the same origin as the API, so the browser needs no CORS.
 *
 * <p>Client-side routes such as /dashboard have no file of their own; they
 * get index.html and React Router takes over. Paths under /api/ and paths
 * that look like files (they contain a dot) are never redirected: a missing
 * one is a real 404. Path traversal checks stay with Spring's resolver.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = super.getResource(resourcePath, location);
                        if (requested != null) {
                            return requested;
                        }
                        if (resourcePath.startsWith("api/") || resourcePath.contains(".")) {
                            return null;
                        }
                        return super.getResource("index.html", location);
                    }
                });
    }
}
