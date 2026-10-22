package com.redator.corretor.config;

import com.redator.corretor.service.LocalDataDirectories;
import com.redator.corretor.service.LocalUserContext;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.profile-databases", havingValue = "true")
public class ProfileDatabaseConfiguration {
    @Bean DataSource profileDataSource(LocalDataDirectories directories, LocalUserContext users) {
        return new ProfileRoutingDataSource(directories, users);
    }
}
