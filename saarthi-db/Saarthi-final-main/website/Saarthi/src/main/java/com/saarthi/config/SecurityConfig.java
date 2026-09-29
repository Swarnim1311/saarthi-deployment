package com.saarthi.config;

import com.saarthi.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter) {

        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        ))

                .authorizeHttpRequests(auth -> auth

                    // Public authentication/bootstrap endpoints
                    .requestMatchers(
                            HttpMethod.POST,
                            "/api/panchayat-officials"
                    ).permitAll()

                    .requestMatchers(
                            "/api/panchayat-officials/login"
                    ).permitAll()

                    // Public health endpoint
                    .requestMatchers(
                            "/actuator/health"
                    ).permitAll()

                    // Public frontend
                    .requestMatchers(
                            "/",
                            "/index.html",
                            "/portal.html",
                            "/**/*.html",
                            "/**/*.css",
                            "/**/*.js",
                            "/images/**",
                            "/favicon.ico"
                    ).permitAll()

                    // Everything else requires JWT
                    .anyRequest().authenticated()
                )

                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}