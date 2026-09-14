package com.paytm.exercise.wallet.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.URI;

@Configuration
public class DataSourceConfig {

    /**
     * Managed-Postgres hosts (Render, Railway, Fly, Koyeb, Heroku) hand out a libpq URI of the
     * form {@code postgresql://user:pass@host:port/db?sslmode=require}. The PostgreSQL JDBC driver
     * does not accept credentials in the authority: it reads {@code user:pass@host} as a hostname
     * and dies with UnknownHostException. So split the userinfo out into explicit
     * username/password and rebuild a driver-legal {@code jdbc:postgresql://host:port/db} URL.
     */
    @Bean
    DataSource dataSource(DataSourceProperties properties) {
        normalizeUrl(properties);
        return properties.initializeDataSourceBuilder().build();
    }

    static void normalizeUrl(DataSourceProperties properties) {
        String url = properties.getUrl();
        if (url == null || url.startsWith("jdbc:")) {
            return;
        }
        if (!url.startsWith("postgres://") && !url.startsWith("postgresql://")) {
            return;
        }

        URI uri = URI.create(url);
        StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() != -1) {
            jdbcUrl.append(':').append(uri.getPort());
        }
        jdbcUrl.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            jdbcUrl.append('?').append(uri.getRawQuery());
        }
        properties.setUrl(jdbcUrl.toString());

        // Credentials embedded in the URI win over any separately configured pair, because the
        // host rotates them together with the URI.
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int separator = userInfo.indexOf(':');
            properties.setUsername(separator < 0 ? userInfo : userInfo.substring(0, separator));
            properties.setPassword(separator < 0 ? null : userInfo.substring(separator + 1));
        }
    }
}
