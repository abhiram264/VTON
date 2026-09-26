package com.aifashionvault.vault;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import com.aifashionvault.normalization.ProductCategory;
import com.aifashionvault.shared.*;
import com.aifashionvault.vault.VaultContracts.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties="spring.autoconfigure.exclude=")
class JdbcVaultStoreTest {
 static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }
 private static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18").withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));
 @Autowired DataSource dataSource; @Autowired JdbcTemplate jdbc; JdbcVaultStore store;
 @DynamicPropertySource static void db(DynamicPropertyRegistry r){POSTGRES.start();r.add("vault.db.url",POSTGRES::getJdbcUrl);r.add("vault.db.username",POSTGRES::getUsername);r.add("vault.db.password",POSTGRES::getPassword);r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);}
 @BeforeEach void setup(){jdbc.update("TRUNCATE vault_item, source_reference, users CASCADE");store=new JdbcVaultStore(dataSource);}
 @Test void roundTripsJsonAndEnforcesOwnershipAndFilters(){UserId owner=user(),foreign=user();Instant now=Instant.parse("2026-08-30T12:00:00Z");VaultItem a=item(owner,now,"A%_\\","Brand",ProductCategory.TOP,"Black");VaultItem b=item(owner,now.plusSeconds(1),"Coat","Other",ProductCategory.ACCESSORY,"Blue");store.insert(a);store.insert(b);assertThat(store.findByIdForUser(a.id(),owner)).contains(a);assertThat(store.findByIdForUser(a.id(),foreign)).isEmpty();assertThat(store.listForUser(owner,new Filters(ProductCategory.TOP,null,"brand","black","%_\\",null,LocalDate.parse("2026-08-30")))).containsExactly(a);assertThat(store.listForUser(owner,new Filters(null,null,null,null,null,null,null))).containsExactly(b,a);}
 @Test void patchDistinguishesOmittedNullAndValue(){UserId owner=user();VaultItem item=item(owner,Instant.now(),"Name","Brand",ProductCategory.TOP,"Black");store.insert(item);Patch patch=new Patch(JsonNullable.undefinedValue(),JsonNullable.of(null),JsonNullable.of(ProductCategory.UNKNOWN),JsonNullable.undefinedValue(),JsonNullable.undefinedValue(),JsonNullable.undefinedValue(),JsonNullable.undefinedValue());VaultItem updated=store.patchForUser(item.id(),owner,patch).orElseThrow();assertThat(updated.name()).isEqualTo("Name");assertThat(updated.brand()).isNull();assertThat(updated.category()).isEqualTo(ProductCategory.UNKNOWN);}
 @Test void collectionFilterIsOwnerScoped(){UserId owner=user(),foreign=user();VaultItem item=item(owner,Instant.now(),"Name",null,null,null);store.insert(item);UUID collection=UUID.randomUUID();jdbc.update("INSERT INTO collection(id,user_id,name,created_at,updated_at) VALUES(?,?,?,now(),now())",collection,owner.value(),"owned");jdbc.update("INSERT INTO collection_item(collection_id,vault_item_id,user_id,created_at) VALUES(?,?,?,now())",collection,item.id().value(),owner.value());assertThat(store.listForUser(owner,new Filters(null,collection,null,null,null,null,null))).containsExactly(item);assertThat(store.listForUser(foreign,new Filters(null,collection,null,null,null,null,null))).isEmpty();}
 private UserId user(){UserId id=new UserId(UUID.randomUUID());jdbc.update("INSERT INTO users(id,created_at,updated_at) VALUES(?,now(),now())",id.value());return id;}
 private VaultItem item(UserId user,Instant at,String name,String brand,ProductCategory category,String color){return new VaultItem(new VaultItemId(UUID.randomUUID()),user,new StorageKey("users/"+user.value()+"/vault/"+UUID.randomUUID()+".jpg"),null,name,brand,category,null,color,null,new ObjectMapper().readTree("{\"nested\":{\"ok\":true}}"),at);}
}
