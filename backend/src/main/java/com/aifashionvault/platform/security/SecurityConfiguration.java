package com.aifashionvault.platform.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import com.aifashionvault.platform.web.RequestIdFilter;
import com.aifashionvault.user.IdentityProvisioning;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(
		HttpSecurity http,
		ObjectProvider<ExternalTokenVerifier> tokenVerifiers,
		ObjectProvider<IdentityProvisioning> identityProvisioning
	) throws Exception {
		ExternalTokenVerifier verifier = tokenVerifiers.getIfAvailable(() -> token -> {
			throw new TokenVerificationException(TokenVerificationFailure.UNVERIFIABLE);
		});
		ExternalBearerAuthenticationFilter filter =
			new ExternalBearerAuthenticationFilter(verifier, identityProvisioning);
		http
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
				.anyRequest().authenticated())
			.addFilterBefore(new RequestIdFilter(), AuthorizationFilter.class)
			.addFilterBefore(filter, AuthorizationFilter.class);
		return http.build();
	}
}
