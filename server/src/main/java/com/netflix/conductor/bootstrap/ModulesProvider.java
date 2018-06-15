package com.netflix.conductor.bootstrap;

import com.google.inject.AbstractModule;
import com.google.inject.ProvisionException;

import com.netflix.conductor.core.config.Configuration;
import com.netflix.conductor.dao.RedisWorkflowModule;
import com.netflix.conductor.dao.es.EmbeddedElasticSearch;
import com.netflix.conductor.dao.es.index.ElasticSearchModule;
import com.netflix.conductor.dao.es5.EmbeddedElasticSearchV5;
import com.netflix.conductor.dao.es5.index.ElasticSearchModuleV5;
import com.netflix.conductor.dao.mysql.MySQLWorkflowModule;
import com.netflix.conductor.server.DynomiteClusterModule;
import com.netflix.conductor.server.JerseyModule;
import com.netflix.conductor.server.LocalRedisModule;
import com.netflix.conductor.server.RedisClusterModule;
import com.netflix.conductor.server.ServerModule;
import com.netflix.conductor.server.SwaggerModule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Provider;

// TODO Investigate whether this should really be a ThrowingProvider.
public class ModulesProvider implements Provider<List<AbstractModule>> {
    private static final Logger logger = LoggerFactory.getLogger(ModulesProvider.class);

    private final Configuration configuration;
    
    @Inject
    public ModulesProvider(Configuration configuration){
        this.configuration = configuration;
    }
    
    @Override
    public List<AbstractModule> get() {
        List<AbstractModule> modulesToLoad = new ArrayList<>();

        modulesToLoad.addAll(selectModulesToLoad());
        modulesToLoad.addAll(configuration.getAdditionalModules());

        return modulesToLoad;
    }

    private List<AbstractModule> selectModulesToLoad() {
        Configuration.DB database = null;
        List<AbstractModule> modules = new ArrayList<>();

        try {
            database = configuration.getDB();
        } catch (IllegalArgumentException ie) {
            final String message = "Invalid db name: " + configuration.getDBString()
                    + ", supported values are: " + Arrays.toString(Configuration.DB.values());
            logger.error(message);
            throw new ProvisionException(message,ie);
        }

        switch (database) {
            case REDIS:
            case DYNOMITE:
                modules.add(new DynomiteClusterModule());
                modules.add(new RedisWorkflowModule());
                logger.info("Starting conductor server using dynomite/redis cluster.");
                break;

            case MYSQL:
                modules.add(new MySQLWorkflowModule());
                modules.add(new MySQLWorkflowModule());
                logger.info("Starting conductor server using MySQL data store", database);
                break;
            case MEMORY:
                // TODO This ES logic should probably live elsewhere.
                try {
                    if (
                            configuration.getIntProperty(
                                    "workflow.elasticsearch.version",
                                    2
                            ) == 5) {
                        EmbeddedElasticSearchV5.start();
                    } else {
                        // Use ES2 as default.
                        EmbeddedElasticSearch.start();
                    }
                    if (System.getProperty("workflow.elasticsearch.url") == null) {
                        System.setProperty("workflow.elasticsearch.url", "localhost:9300");
                    }
                    if (System.getProperty("workflow.elasticsearch.index.name") == null) {
                        System.setProperty("workflow.elasticsearch.index.name", "conductor");
                    }
                } catch (Exception e) {
                    logger.error("Error starting embedded elasticsearch.  Search functionality will be impacted: " + e.getMessage(), e);
                }

                modules.add(new LocalRedisModule());
                modules.add(new RedisWorkflowModule());
                logger.info("Starting conductor server using in memory data store");
                break;

            case REDIS_CLUSTER:
                modules.add(new RedisClusterModule());
                modules.add(new RedisWorkflowModule());
                logger.info("Starting conductor server using redis_cluster.");
                break;
        }

        if (configuration.getIntProperty("workflow.elasticsearch.version", 2) == 5) {
            modules.add(new ElasticSearchModuleV5());
        } else {
            modules.add(new ElasticSearchModule());
        }

        if (configuration.getJerseyEnabled()) {
            modules.add(new JerseyModule());
            modules.add(new SwaggerModule());
        }

        modules.add(new ServerModule());

        return modules;
    }
}
