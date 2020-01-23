/*******************************************************************************
 * Copyright (c) 2012 GigaSpaces Technologies Ltd. All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.openspaces.persistency.cassandra;

import org.openspaces.core.cluster.ClusterInfo;
import org.openspaces.core.cluster.ClusterInfoAware;
import org.openspaces.persistency.cassandra.datasource.CassandraSpaceDataSource;
import org.openspaces.persistency.cassandra.pool.CassandraDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.List;


/**
 * 
 * A {@link FactoryBean} for creating a singleton instance of
 * {@link CassandraSpaceDataSource}.
 * 
 * @since 9.1.1
 * @author Dan Kilman
 */
public class CassandraSpaceDataSourceFactoryBean implements 
    FactoryBean<CassandraSpaceDataSource>, InitializingBean, DisposableBean, ClusterInfoAware {
    private static Logger logger = LoggerFactory.getLogger(CassandraSpaceDataSourceFactoryBean.class);

    private final CassandraSpaceDataSourceConfigurer configurer = new CassandraSpaceDataSourceConfigurer();

    private CassandraSpaceDataSource cassandraSpaceDataSource;

    public void setCassandraDataSource(CassandraDataSource cassandraDataSource){
        configurer.cassandraDataSource(cassandraDataSource);
    }
    /**
     * @see CassandraSpaceDataSourceConfigurer#minimumNumberOfConnections(int)
     */
    public void setMinimumNumberOfConnections(int minimumNumberOfConnections) {
        configurer.minimumNumberOfConnections(minimumNumberOfConnections);
    }

    /**
     * @see CassandraSpaceDataSourceConfigurer#maximumNumberOfConnections(int)
     */
    public void setMaximumNumberOfConnections(int maximumNumberOfConnections) {
        configurer.maximumNumberOfConnections(maximumNumberOfConnections);
    }

    public CassandraSpaceDataSourceFactoryBean setCassandraSpaceDataSource(CassandraSpaceDataSource cassandraSpaceDataSource) {
        this.cassandraSpaceDataSource = cassandraSpaceDataSource;
        return this;
    }

    /**
     * @see CassandraSpaceDataSourceConfigurer#batchLimit(int)
     */
    public void setBatchLimit(int batchLimit) {
        configurer.batchLimit(batchLimit);
    }

    /**
     * @see CassandraSpaceDataSourceConfigurer#initialLoadQueryScanningBasePackages(String[])
     */
    public void setInitialLoadQueryScanningBasePackages(String... initialLoadQueryScanningBasePackages) {
        configurer.initialLoadQueryScanningBasePackages(initialLoadQueryScanningBasePackages);
    }

    public void setInventoryPackages(List<String> inventoryPackages){
        configurer.inventoryPackages(inventoryPackages);
    }

    /**
	 * @see CassandraSpaceDataSourceConfigurer#clusterInfo(org.openspaces.core.cluster.ClusterInfo)
	 */
	@Override
	public void setClusterInfo(ClusterInfo clusterInfo) {
		configurer.clusterInfo(clusterInfo);
	}


    /**
     * @see CassandraSpaceDataSourceConfigurer#augmentInitialLoadEntries(boolean)
     */
    public void augmentInitialLoadEntries(boolean augmentInitialLoadEntries) { configurer.augmentInitialLoadEntries(augmentInitialLoadEntries); }

    @Override
    public void afterPropertiesSet() {
        this.cassandraSpaceDataSource=configurer.create();
        logger.info("Created {}",cassandraSpaceDataSource);
    }

    @Override
    public CassandraSpaceDataSource getObject() throws Exception {
        return cassandraSpaceDataSource;
    }

    @Override
    public Class<?> getObjectType() {
        return CassandraSpaceDataSource.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    @Override
    public void destroy() throws Exception {
        cassandraSpaceDataSource.close();
    }
}
