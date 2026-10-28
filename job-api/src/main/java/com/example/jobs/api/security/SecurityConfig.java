package com.example.jobs.api.security;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import com.example.jobs.api.config.JobProperties;

/**
 * Authentication: HTTP Basic against configured users (stateless, no session, no CSRF needed).
 * Authorization: role USER for the API, ADMIN for operational endpoints (metrics).
 *
 * <p>Production: swap {@code httpBasic} for {@code oauth2ResourceServer().jwt()} against an OIDC
 * provider; roles come from token claims. Nothing else changes.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasRole("USER")
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .httpBasic(withDefaults());
        return http.build();
    }

    @Bean
    UserDetailsService userDetailsService(JobProperties properties) {
        InMemoryUserDetailsManager manager = new InMemoryUserDetailsManager();
        for (JobProperties.Security.User u : properties.security().users()) {
            manager.createUser(User.withUsername(u.username())
                    .password(u.password())
                    .roles(u.roles().toArray(String[]::new))
                    .build());
        }
        return manager;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
