package com.aifashionvault.platform.web;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aifashionvault.importer.ImportMediaAccess;
import com.aifashionvault.importer.ImportResolver;
import com.aifashionvault.importer.JdbcShareImportStore;
import com.aifashionvault.importer.ResolutionContext;
import com.aifashionvault.importer.SourceResolverPrecedence;
import com.aifashionvault.importer.SourceResolverRegistration;
import com.aifashionvault.importer.SourceResolverRegistry;
import com.aifashionvault.platform.importer.GenericOpenGraphResolver;
import com.aifashionvault.platform.importer.SourceResolverMetrics;
import com.aifashionvault.platform.importer.GenericUrlResolver;
import com.aifashionvault.platform.importer.ImportDns;
import com.aifashionvault.platform.importer.ImportHttpFetcher;
import com.aifashionvault.platform.importer.SafeImportHttpClient;
import com.aifashionvault.platform.importer.SharedImageResolver;
import com.aifashionvault.platform.objectstore.ImportObjectWriter;
import com.aifashionvault.platform.objectstore.ObjectStoreImportMediaAccess;
import com.aifashionvault.platform.objectstore.ImageInspector;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.S3ObjectStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import com.aifashionvault.shared.ObservationFacade;
import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

/**
 * Production wiring for the share-import API. Registered through FactoryBeans
 * (vton-test-datasource-beans convention) so beans stay absent in slim
 * contexts without a DataSource and appear only when the late DataSource is
 * visible.
 */
@Configuration(proxyBeanMethods = false)
public class ShareImportConfiguration {

	@Bean
	FactoryBean<ShareImportMediaAccess> shareImportMediaAccess(ObjectProvider<ObjectStore> objectStores,
			ObjectProvider<ImageInspector> imageInspectors) {
		return new ShareImportMediaAccessFactoryBean(objectStores, imageInspectors);
	}

	@Bean
	FactoryBean<ShareImportResolverChain> shareImportResolverChain(
			ObjectProvider<ObjectStore> objectStores,
			ObjectProvider<ImageInspector> imageInspectors,
			ObjectProvider<software.amazon.awssdk.services.s3.S3Client> s3Clients,
			ObjectProvider<MeterRegistry> meterRegistries,
			ObjectProvider<ObservationRegistry> observationRegistries) {
		return new ShareImportResolverChainFactoryBean(objectStores, imageInspectors, s3Clients, meterRegistries,
			observationRegistries);
	}

	@Bean
	FactoryBean<ShareImportService> shareImportService(
			ObjectProvider<DataSource> dataSources,
			ObjectProvider<ShareImportMediaAccess> mediaAccesses,
			ObjectProvider<ShareImportResolverChain> chains,
			ObjectProvider<com.aifashionvault.analytics.ProductMetrics> productMetrics) {
		return new ShareImportServiceFactoryBean(dataSources, mediaAccesses, chains, productMetrics);
	}

	private record Adapters(ObjectStore store, ImageInspector inspector) {
	}

	private static Adapters adapters(ObjectProvider<ObjectStore> stores, ObjectProvider<ImageInspector> inspectors) {
		ObjectStore store = stores.getIfAvailable();
		ImageInspector inspector = inspectors.getIfAvailable();
		if (store == null || inspector == null) {
			return null;
		}
		return new Adapters(store, inspector);
	}

	static final class ShareImportMediaAccessFactoryBean implements FactoryBean<ShareImportMediaAccess> {

		private final ObjectProvider<ObjectStore> stores;
		private final ObjectProvider<ImageInspector> inspectors;

		ShareImportMediaAccessFactoryBean(ObjectProvider<ObjectStore> stores,
				ObjectProvider<ImageInspector> inspectors) {
			this.stores = stores;
			this.inspectors = inspectors;
		}

		@Override
		public ShareImportMediaAccess getObject() {
			Adapters adapters = adapters(stores, inspectors);
			if (adapters == null) {
				return null;
			}
			return new ObjectStoreShareMedia(adapters.store(), adapters.inspector());
		}

		@Override
		public Class<?> getObjectType() {
			return ShareImportMediaAccess.class;
		}

		@Override
		public boolean isSingleton() {
			return true;
		}
	}

	static final class ShareImportResolverChainFactoryBean implements FactoryBean<ShareImportResolverChain> {

		private final ObjectProvider<ObjectStore> stores;
		private final ObjectProvider<ImageInspector> inspectors;
		private final ObjectProvider<software.amazon.awssdk.services.s3.S3Client> s3Clients;
		private final ObjectProvider<MeterRegistry> meterRegistries;
		private final ObjectProvider<ObservationRegistry> observationRegistries;

		ShareImportResolverChainFactoryBean(ObjectProvider<ObjectStore> stores,
				ObjectProvider<ImageInspector> inspectors,
				ObjectProvider<software.amazon.awssdk.services.s3.S3Client> s3Clients,
				ObjectProvider<MeterRegistry> meterRegistries,
				ObjectProvider<ObservationRegistry> observationRegistries) {
			this.stores = stores;
			this.inspectors = inspectors;
			this.s3Clients = s3Clients;
			this.meterRegistries = meterRegistries;
			this.observationRegistries = observationRegistries;
		}

		@Override
		public ShareImportResolverChain getObject() {
			Adapters adapters = adapters(stores, inspectors);
			software.amazon.awssdk.services.s3.S3Client s3 = s3Clients.getIfAvailable();
			if (adapters == null) {
				return null;
			}
			return PlatformResolverChain.over(adapters.store(), adapters.inspector(), s3,
				meterRegistries.getIfAvailable(), observationRegistries.getIfAvailable());
		}

		@Override
		public Class<?> getObjectType() {
			return ShareImportResolverChain.class;
		}

		@Override
		public boolean isSingleton() {
			return true;
		}
	}

	static final class ShareImportServiceFactoryBean implements FactoryBean<ShareImportService> {

		private final ObjectProvider<DataSource> dataSources;
		private final ObjectProvider<ShareImportMediaAccess> mediaAccesses;
		private final ObjectProvider<ShareImportResolverChain> chains;
		private final ObjectProvider<com.aifashionvault.analytics.ProductMetrics> productMetrics;

		ShareImportServiceFactoryBean(
				ObjectProvider<DataSource> dataSources,
				ObjectProvider<ShareImportMediaAccess> mediaAccesses,
				ObjectProvider<ShareImportResolverChain> chains,
				ObjectProvider<com.aifashionvault.analytics.ProductMetrics> productMetrics
		) {
			this.dataSources = dataSources;
			this.mediaAccesses = mediaAccesses;
			this.chains = chains;
			this.productMetrics = productMetrics;
		}

		@Override
		public ShareImportService getObject() {
			DataSource dataSource = dataSources.getIfAvailable();
			ShareImportMediaAccess media = mediaAccesses.getIfAvailable();
			ShareImportResolverChain chain = chains.getIfAvailable();
			if (dataSource == null || media == null || chain == null) {
				return null;
			}
			return new ShareImportService(new JdbcShareImportStore(dataSource), media, chain,
				productMetrics == null ? null : productMetrics.getIfAvailable());
		}

		@Override
		public Class<?> getObjectType() {
			return ShareImportService.class;
		}

		@Override
		public boolean isSingleton() {
			return true;
		}
	}

	/** Owner-scoped media access over the U2 object-store adapter overloads. */
	static final class ObjectStoreShareMedia implements ShareImportMediaAccess {

		private final ObjectStore store;
		private final ImageInspector inspector;

		ObjectStoreShareMedia(ObjectStore store, ImageInspector inspector) {
			this.store = Objects.requireNonNull(store, "store");
			this.inspector = Objects.requireNonNull(inspector, "inspector");
		}

		@Override
		public Optional<ImportMediaAccess.VerifiedImage> findVerifiedUpload(UserId ownerId, UploadId uploadId) {
			return ownerScoped(ownerId).findVerifiedUpload(ownerId, uploadId);
		}

		@Override
		public ImportMediaAccess.ClaimOutcome claimVerifiedUpload(
			UserId ownerId, ImportMediaAccess.VerifiedImage upload, StorageKey destination) {
			return ownerScoped(ownerId).claimVerifiedUpload(ownerId, upload, destination);
		}

		@Override
		public boolean publishFetchedImage(
			UserId ownerId, InputStream content, long contentLength, StorageKey destination) {
			// publishFetchedImage on the unscoped adapter requires an owner-scoped
			// writer; URL-import publishing belongs to the OG resolver chain, which
			// carries its own owner-scoped adapter.
			throw new UnsupportedOperationException(
				"publishFetchedImage is owned by the owner-scoped OG resolver adapter");
		}

		@Override
		public void deleteObject(StorageKey key) {
			new com.aifashionvault.platform.objectstore.ObjectStoreImportMediaAccess(store, inspector)
				.deleteObject(key);
		}

		/**
		 * Short-lived signed read for the resolved import object. The resolver
		 * publishes every import object into the VAULT boundary, so the preview
		 * signs that same boundary; the URL is time-boxed and re-issuable. An
		 * unavailable store yields empty (the API answers 503) rather than a
		 * fabricated capability.
		 */
		@Override
		public Optional<com.aifashionvault.platform.objectstore.PresignedAccess> presignPreview(
				UserId ownerId, StorageKey key) {
			if (key == null) {
				return Optional.empty();
			}
			try {
				return Optional.of(store.presignGet(
					new com.aifashionvault.platform.objectstore.ObjectRef(
						com.aifashionvault.shared.StorageDomain.VAULT, key.value()),
					PREVIEW_TTL));
			}
			catch (com.aifashionvault.platform.objectstore.ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
				return Optional.empty();
			}
		}

		/** Preview capability lifetime: short so a leaked URL expires quickly. */
		private static final java.time.Duration PREVIEW_TTL = java.time.Duration.ofMinutes(5);

		private com.aifashionvault.platform.objectstore.ObjectStoreImportMediaAccess ownerScoped(UserId ownerId) {
			return new com.aifashionvault.platform.objectstore.ObjectStoreImportMediaAccess(store, inspector, null,
				ownerId);
		}
	}

	/** Registry-ordered resolver factories create fresh resolvers for each attempt. */
	static final class PlatformResolverChain implements ShareImportResolverChain {

		private final ObjectStore store;
		private final ImageInspector inspector;
		private final ImportObjectWriter writer;
		private final SourceResolverRegistry registry;

		PlatformResolverChain(ObjectStore store, ImageInspector inspector, ImportObjectWriter writer,
				MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
			this.store = Objects.requireNonNull(store, "store");
			this.inspector = Objects.requireNonNull(inspector, "inspector");
			this.writer = Objects.requireNonNull(writer, "writer");
			SourceResolverMetrics metrics = new SourceResolverMetrics(meterRegistry,
				observationRegistry == null ? null : new ObservationFacade(observationRegistry));
			ImportHttpFetcher fetcher = new SafeImportHttpClient();
			ImportDns dns = new ImportDns();
			this.registry = new SourceResolverRegistry(List.of(
				new SourceResolverRegistration("SHARED_IMAGE", SourceResolverPrecedence.SHARED_IMAGE,
					context -> metrics.decorate("SHARED_IMAGE", new SharedImageResolver(
						context.mediaAccess(), context.ownerId(), context.importId()))),
				new SourceResolverRegistration("GENERIC_OPEN_GRAPH", SourceResolverPrecedence.GENERIC_OPEN_GRAPH,
					context -> metrics.decorate("GENERIC_OPEN_GRAPH", new GenericOpenGraphResolver(
						fetcher, dns, inspector, context.mediaAccess(), context.ownerId(), context.importId(),
						context.attemptToken()))),
				new SourceResolverRegistration("GENERIC_URL", SourceResolverPrecedence.GENERIC_URL,
					context -> metrics.decorate("GENERIC_URL", new GenericUrlResolver()))));
		}

		static PlatformResolverChain over(ObjectStore store, ImageInspector inspector,
				software.amazon.awssdk.services.s3.S3Client s3, MeterRegistry meterRegistry) {
			return over(store, inspector, s3, meterRegistry, null);
		}

		static PlatformResolverChain over(ObjectStore store, ImageInspector inspector,
				software.amazon.awssdk.services.s3.S3Client s3, MeterRegistry meterRegistry,
				ObservationRegistry observationRegistry) {
			if (!(store instanceof S3ObjectStore) || s3 == null) {
				return null;
			}
			return new PlatformResolverChain(store, inspector, new ImportObjectWriter((S3ObjectStore) store, s3),
				meterRegistry, observationRegistry);
		}

		@Override
		public ImportResolver chain(UserId ownerId, ShareImportId importId, String attemptToken) {
			ResolutionContext context = new ResolutionContext(ownerId, importId, attemptToken, ownerMedia(ownerId));
			return new ImportResolver(registry.createResolvers(context));
		}

		ImportMediaAccess ownerMedia(UserId ownerId) {
			return new ObjectStoreImportMediaAccess(store, inspector, writer, ownerId);
		}
	}
}
