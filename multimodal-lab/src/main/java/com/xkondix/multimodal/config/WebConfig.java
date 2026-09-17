package com.xkondix.multimodal.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the Vite dev server.
 *
 * THE SYMPTOM THIS FIXES IS MISLEADING. A multipart POST is a "simple"
 * request, so the browser sends it WITHOUT a preflight: the server receives
 * it, generates the image, writes 1.7 MB to disk, logs a clean success — and
 * then the browser throws the response away because it carries no
 * Access-Control-Allow-Origin header. The page shows "Failed to fetch" while
 * the backend log shows everything worked.
 *
 * Worth keeping in mind when reading the other modules' logs too: a server
 * that says it succeeded has not necessarily delivered anything.
 *
 * Origins are listed explicitly rather than "*" because the media route
 * serves files, and a wildcard there is a habit better not formed — even on
 * a laptop.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:3000",   // chat-ui (vite preview / docker)
                        "http://localhost:5173")   // chat-ui (vite dev default)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
