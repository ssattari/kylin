/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kylin.rest.config;

import java.util.Properties;

import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.persistence.metadata.jdbc.JdbcUtil;
import org.apache.kylin.common.util.Unsafe;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.session.StoreType;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class KylinPropertySourceConfiguration implements EnvironmentPostProcessor, Ordered {

    private static final String SYSTEM_PROPERTY_PREFIX = "kylin.system.property.";
    private static final String SPRING_SESSION_CLEAN_CRON = "spring.session.jdbc.cleanup-cron";
    public static final String SPRING_SESSION_JDBC_CLEANUP_FLAG = "spring.session.jdbc.cleanup-flag";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {

        if (environment.getPropertySources().contains("bootstrap")) {
            return;
        }

        log.debug("use kylinconfig as spring properties");
        val propertySources = environment.getPropertySources();
        val kylinConfig = KylinConfig.getInstanceFromEnv();
        val storageURL = kylinConfig.getMetadataUrl();
        if (storageURL.getScheme().equals("jdbc")) {
            JdbcUtil.datasourceParameters(storageURL)
                    .forEach((key, value) -> kylinConfig.setProperty("spring.datasource." + key, value.toString()));
        }
        PropertySource<String> source = new PropertySource<String>("kylin") {
            Properties properties = KylinConfig.getInstanceFromEnv().exportToProperties();

            @Override
            public Object getProperty(String name) {
                return properties.getProperty(name);
            }
        };

        setSystemProperty(kylinConfig.exportToProperties());
        discardJDBCCleanSessionProperties(kylinConfig);
        propertySources.addAfter("systemProperties", source);
    }

    public void setSystemProperty(Properties properties) {
        for (String propertyName : properties.stringPropertyNames()) {
            if (propertyName.startsWith(SYSTEM_PROPERTY_PREFIX)) {
                String propertyValue = properties.getProperty(propertyName);
                Unsafe.setProperty(propertyName.replaceFirst(SYSTEM_PROPERTY_PREFIX, ""), propertyValue);
            }
        }
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1020;
    }

    /**
     * when spring store type is jdbc, we should do following:
     * 1. use {@link org.apache.kylin.rest.service.task.SpringSessionCleanScheduler} rather than spring session cleanup
     * 2. discard spring session cleanup task
     */
    public void discardJDBCCleanSessionProperties(KylinConfig kylinConfig) {
        if (kylinConfig.getSpringStoreType().equalsIgnoreCase(StoreType.JDBC.toString())) {
            log.info("Discard JDBC clean session properties.");
            Unsafe.setProperty(SPRING_SESSION_CLEAN_CRON, "-");
            Unsafe.setProperty(SPRING_SESSION_JDBC_CLEANUP_FLAG, "false");
        }
    }
}
