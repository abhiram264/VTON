package com.aifashionvault.generation;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * C19: the approved generation admission caps are operational configuration,
 * not amounts compiled into a call site. An operator moves them without a
 * rebuild by setting {@code vault.generation.admission.*} (YAML, or the
 * relaxed-binding environment form
 * {@code VAULT_GENERATION_ADMISSION_*}) for the deployment.
 *
 * The declared defaults reproduce the values this application shipped with as
 * {@link AdmissionPolicy#DEFAULT} (5 accepted jobs per 24h, 2 active jobs,
 * 6 products per job). They are deliberately NOT an approved launch budget:
 * the owner sets the real numbers in deployment configuration, which is what
 * makes them explicable rather than unexplained source constants.
 *
 * Out-of-range values are refused twice: {@code @Min(1)} rejects them at bind
 * time where a JSR-303 validator is present (the application supplies one
 * through spring-boot-starter-validation), and {@link #toPolicy()} goes
 * through {@link AdmissionPolicy}'s own compact constructor, so no
 * out-of-range policy can ever reach the store even if the annotation is not
 * processed.
 */
@Validated
@ConfigurationProperties(prefix = "vault.generation.admission")
public class AdmissionCapsProperties {

	private int acceptedJobsPer24Hours = AdmissionPolicy.DEFAULT.acceptedJobsPer24Hours();
	private int maxActiveJobs = AdmissionPolicy.DEFAULT.maxActiveJobs();
	private int maxProductsPerJob = AdmissionPolicy.DEFAULT.maxProductsPerJob();

	public int getAcceptedJobsPer24Hours() {
		return acceptedJobsPer24Hours;
	}

	public void setAcceptedJobsPer24Hours(int acceptedJobsPer24Hours) {
		this.acceptedJobsPer24Hours = acceptedJobsPer24Hours;
	}

	public int getMaxActiveJobs() {
		return maxActiveJobs;
	}

	public void setMaxActiveJobs(int maxActiveJobs) {
		this.maxActiveJobs = maxActiveJobs;
	}

	public int getMaxProductsPerJob() {
		return maxProductsPerJob;
	}

	public void setMaxProductsPerJob(int maxProductsPerJob) {
		this.maxProductsPerJob = maxProductsPerJob;
	}

	/**
	 * The store-side policy this configuration describes. Validates through
	 * {@link AdmissionPolicy}, so an out-of-range value can never be handed to
	 * the store even if bind-time validation is bypassed.
	 */
	public AdmissionPolicy toPolicy() {
		return new AdmissionPolicy(acceptedJobsPer24Hours, maxActiveJobs, maxProductsPerJob);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AdmissionCapsProperties.class)
	static class Registrar {
	}
}
