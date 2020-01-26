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
package org.openspaces.persistency.cassandra.datasource;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.internal.mapper.entity.EntityHelperBase;
import com.gigaspaces.datasource.*;
import com.gigaspaces.document.SpaceDocument;
import com.gigaspaces.metadata.SpaceTypeDescriptor;
import com.j_spaces.kernel.pool.IResourceFactory;
import com.j_spaces.kernel.pool.IResourcePool;
import com.j_spaces.kernel.pool.IResourceProcedure;
import com.j_spaces.kernel.pool.ResourcePool;
import org.openspaces.core.cluster.ClusterInfo;
import org.openspaces.persistency.ClusterInfoAwareSpaceDataSource;
import org.openspaces.persistency.cassandra.error.SpaceCassandraDataSourceException;
import org.openspaces.persistency.cassandra.iterator.CassandraInitialDataLoadIterator;
import org.openspaces.persistency.cassandra.pool.CassandraDataSource;
import org.openspaces.persistency.cassandra.pool.ConnectionResource;
import org.openspaces.persistency.cassandra.pool.ConnectionResourceFactory;
import org.openspaces.persistency.cassandra.types.CassandraTypeInfo;
import org.openspaces.persistency.support.SpaceTypeDescriptorContainer;
import org.openspaces.persistency.support.TypeDescriptorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.openspaces.persistency.cassandra.utils.ReflectionsUtils.getClassesInPackage;

/**
 * 
 * A Cassandra implementation of {@link com.gigaspaces.datasource.SpaceDataSource}.
 * 
 * @since 9.1.1
 * @author Dan Kilman
 */
public class CassandraSpaceDataSource extends ClusterInfoAwareSpaceDataSource {
    
    public static final String                     CQL_VERSION  = "4";
    
    private static final Logger                   logger = LoggerFactory.getLogger(CassandraSpaceDataSource.class);

    private final IResourcePool<ConnectionResource> connectionPool;
    private final List<String> entitiesPackages;
    private final int                               batchLimit;
    //private final DefaultConsistencyLevel           readConsistencyLevel;
    //private final DefaultConsistencyLevel           writeConsistencyLevel;

    private final Object                            lock         = new Object();
    private boolean                                 closed       = false;
    private ConcurrentMap<String, CassandraTypeInfo> initialMetaLoadEntriesMap = new ConcurrentHashMap<>();
    private String                                  releaseVersion=null;
    private String                                  nativeProtocolVersion=null;
    private String                                  defaultKeyspace=null;

    public CassandraSpaceDataSource(
            CassandraDataSource cassandraDataSource,
            String       defaultKeyspace,
            List<String> entitiesPackages,
            int minimumNumberOfConnections,
            int maximumNumberOfConnections,
            int batchLimit,
            String[] initialLoadQueryScanningBasePackages,
            boolean augmentInitialLoadEntries,
			ClusterInfo clusterInfo	) {

        if (cassandraDataSource == null) {
            throw new IllegalArgumentException("dataSource must be set");
        }

        if (minimumNumberOfConnections <= 0) {
            throw new IllegalArgumentException("minimumNumberOfConnections must be positive number");
        }
        
        if (maximumNumberOfConnections < minimumNumberOfConnections) {
            throw new IllegalArgumentException("maximumNumberOfConnections must not be smaller than" +
                                               "minimumNumberOfConnections");
        }
        
        if (batchLimit <= 0) {
            throw new IllegalArgumentException("batchSize must be a positive number");
        }


        this.entitiesPackages = entitiesPackages;
        this.batchLimit = batchLimit;
        this.defaultKeyspace=defaultKeyspace;

        IResourceFactory<ConnectionResource> resourceFactory = new ConnectionResourceFactory(cassandraDataSource);
        connectionPool = new ResourcePool<>(resourceFactory,
                minimumNumberOfConnections,
                maximumNumberOfConnections);
        initVersion();
        logger.info("Cassandra data source is connected to a server CQLversion={}; release version={}",nativeProtocolVersion,releaseVersion);
        if(!CQL_VERSION.equals(nativeProtocolVersion)){
            logger.warn("Best with CQL version 4, version used is {}",nativeProtocolVersion);
        }

        this.initialLoadQueryScanningBasePackages = initialLoadQueryScanningBasePackages;
        this.augmentInitialLoadEntries = augmentInitialLoadEntries;
		this.clusterInfo = clusterInfo;
    }

    private void initVersion(){
        if(releaseVersion==null||nativeProtocolVersion==null) {
            ConnectionResource resource = connectionPool.getResource();
            try {
                CqlSession session = resource.getSession();
                ResultSet rs = session.execute("select release_version,native_protocol_version from system.local");
                Row row = rs.one();
                releaseVersion = row.getString("release_version");
                nativeProtocolVersion = row.getString("native_protocol_version");
            }
            finally {
                resource.release();
            }
            logger.info("retrieve server release version {}",releaseVersion);
        }
    }

    /**
     * Closes open jdbc connections and the hector client connection pool.
     */
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            connectionPool.forAllResources(new IResourceProcedure<ConnectionResource>() {
                public void invoke(ConnectionResource resource) {
                    resource.close();
                }
            });
            closed = true;
        }
    }
    
    @Override
    public DataIterator<Object> getDataIterator(DataSourceQuery query) {
        String typeName = query.getTypeDescriptor().getTypeName();

        CQLQueryContext queryContext = null;
        if (query.supportsTemplateAsDocument()) {
            SpaceDocument templateDocument = query.getTemplateAsDocument();
            Map<String, Object> properties = templateDocument.getProperties();
            queryContext = new CQLQueryContext(properties, null, null);
        } else if (query.supportsAsSQLQuery()) {
            DataSourceSQLQuery sqlQuery = query.getAsSQLQuery();
            Object[] params = sqlQuery.getQueryParameters();
            queryContext = new CQLQueryContext(null, sqlQuery.getQuery(), params);
        } else {
            throw new SpaceCassandraDataSourceException("Unsupported data source query", null);
        }
        return null;
    }

    /*
    private Object getKeyValue(CQLQueryContext queryContext, ColumnFamilyMetadata metadata) {
        if (!queryContext.hasProperties()) {
            return null;
        }
        
        return queryContext.getProperties().get(metadata.getKeyName());
    }*/

    /*
    private boolean templateHasPropertyOtherThanKey(
            CQLQueryContext queryContext,
            ColumnFamilyMetadata metadata) {
        // This test is not really needed as it is only called after getKeyValue returned a
        // value differet than null, and this same test is performed there
        if (!queryContext.hasProperties()) {
            return true;
        }
        
        for (Entry<String, Object> entry : queryContext.getProperties().entrySet()) {
            if (!metadata.getKeyName().equals(entry.getKey()) &&
                entry.getValue() != null) {
                return true;
            }
        }
        
        return false;
    }*/
    
    @Override
    public Object getById(DataSourceIdQuery idQuery) {
        String typeName = idQuery.getTypeDescriptor().getTypeName();
        Object id = idQuery.getId();
        return getByIdImpl(typeName, id);
    }
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public DataIterator<Object> getDataIteratorByIds(DataSourceIdsQuery idsQuery)
    {
        String typeName = idsQuery.getTypeDescriptor().getTypeName();
        Object[] ids = idsQuery.getIds();
//        Map<Object, SpaceDocument> documentsByKeys = hectorClient.readDocumentsByKeys(mapper, typeName, ids);
        return null;//new DataIteratorAdapter<Object>((Iterator)documentsByKeys.values().iterator());
    }
    
    private Object getByIdImpl(String typeName, Object id) {
        return null;//hectorClient.readDocmentByKey(mapper, typeName, id);
    }

    private Stream<Class<? extends EntityHelperBase>> getEntitiesHelperClassOnEntitiesPackage(){
        return this.entitiesPackages.stream()
                .flatMap(p->getClassesInPackage(p).stream())
                .peek(c->logger.info("class {} is EntityHelperBase? {}",c,EntityHelperBase.class.isAssignableFrom(c)))
                .filter(c->EntityHelperBase.class.isAssignableFrom(c))
                .peek(c->logger.info("class {} retained",c))
                .map(c->(Class<? extends EntityHelperBase>)c);
    }

    @Override
    public DataIterator<SpaceTypeDescriptor> initialMetadataLoad() {
        super.initialMetadataLoad();
        logger.info("initialMetadataLoad : searchForEntityHelperBaseOnPackages ");
        List<Class<? extends EntityHelperBase>> entityHelpersClasses = getEntitiesHelperClassOnEntitiesPackage().collect(Collectors.toList());
        logger.info("initialMetadataLoad : found {}",  entityHelpersClasses);

        ConnectionResource resource = connectionPool.getResource();
        try {
            CqlSession session = resource.getSession();

            Set<Class<?>> types = entityHelpersClasses.stream()
                .peek(c->logger.info("getting type for mapper builder {} ",c))
                .map(c->{
                    CassandraTypeInfo cassandraTypeInfo = new CassandraTypeInfo(c,defaultKeyspace,session);
                    initialMetaLoadEntriesMap.put(cassandraTypeInfo.getType().getName(),cassandraTypeInfo);
                    //                 mapper.
                    return c;
                }).collect(Collectors.toSet());
        }
        finally {
            resource.release();
        }

     //   SpaceTypeDescriptorBuilder = new SpaceTypeDescriptorBuilder();
        Map<String, SpaceTypeDescriptorContainer> typeDescriptors = new HashMap<>();

        List<SpaceTypeDescriptor> result = TypeDescriptorUtils.sort(typeDescriptors);

        /*
        Map<String, ColumnFamilyMetadata> columnFamilies = hectorClient.populateColumnFamiliesMetadata(mapper);
        
        if (logger.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Loaded the following types from Cassandra for initial metadata load:")
              .append(StringUtils.NEW_LINE);
            
            for (ColumnFamilyMetadata metadata : columnFamilies.values()) {
                sb.append("\t").append(metadata).append(StringUtils.NEW_LINE);
            }
            
            logger.debug(sb.toString());
        }


        for (ColumnFamilyMetadata metadata : columnFamilies.values()) {
            String typeName = metadata.getTypeName();
            SpaceTypeDescriptorContainer spaceTypeDescriptorContainer = metadata.getTypeDescriptorData();
            typeDescriptors.put(typeName, spaceTypeDescriptorContainer);
            if (augmentInitialLoadEntries) {
                initialLoadEntriesMap.put(typeName, spaceTypeDescriptorContainer.getTypeDescriptor());
            }
        }



         */
        return new DataIteratorAdapter<SpaceTypeDescriptor>(result.iterator());
    }

    @Override
    public DataIterator<Object> initialDataLoad() {
        return new CassandraInitialDataLoadIterator(initialMetaLoadEntriesMap,connectionPool.getResource());
    }
    
    /**
     * Returns <code>false</code>, inheritance is not supported.
     * @return <code>false</code>.
     */
    @Override
    public boolean supportsInheritance() {
        return false;
    }

}
