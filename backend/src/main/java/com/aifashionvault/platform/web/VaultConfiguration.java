package com.aifashionvault.platform.web;

import javax.sql.DataSource;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.vault.JdbcVaultStore;

@Configuration(proxyBeanMethods=false)
class VaultConfiguration {
	@Bean FactoryBean<VaultService> vaultService(ObjectProvider<DataSource> sources,ObjectProvider<ObjectStore> stores){return new FactoryBean<>(){
		public VaultService getObject(){DataSource source=sources.getIfAvailable();ObjectStore store=stores.orderedStream().findFirst().orElse(null);return source==null||store==null?null:new VaultService(new JdbcVaultStore(source),store);}
		public Class<?> getObjectType(){return VaultService.class;} public boolean isSingleton(){return true;}
	};}
}
