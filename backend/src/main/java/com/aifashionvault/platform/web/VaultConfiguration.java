package com.aifashionvault.platform.web;

import javax.sql.DataSource;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.shared.ObservationFacade;
import com.aifashionvault.vault.JdbcVaultStore;
import io.micrometer.observation.ObservationRegistry;

@Configuration(proxyBeanMethods=false)
class VaultConfiguration {
	@Bean FactoryBean<VaultService> vaultService(ObjectProvider<DataSource> sources,ObjectProvider<ObjectStore> stores,ObjectProvider<ObservationRegistry> registries){return new FactoryBean<>(){
		public VaultService getObject(){DataSource source=sources.getIfAvailable();ObjectStore store=stores.getIfAvailable();return source==null||store==null?null:new VaultService(new JdbcVaultStore(source),store,new ObservationFacade(registries.getIfAvailable()));}
		public Class<?> getObjectType(){return VaultService.class;} public boolean isSingleton(){return true;}
	};}
}
