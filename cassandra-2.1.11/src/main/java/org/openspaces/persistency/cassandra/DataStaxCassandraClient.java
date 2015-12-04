package org.openspaces.persistency.cassandra;

import com.datastax.driver.core.*;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.querybuilder.Batch;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import com.datastax.driver.core.schemabuilder.Create;
import com.datastax.driver.core.schemabuilder.SchemaBuilder;
import com.datastax.driver.core.schemabuilder.SchemaStatement;
import com.gigaspaces.document.SpaceDocument;
import com.gigaspaces.internal.utils.StringUtils;
import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openspaces.persistency.cassandra.datasource.CQLQueryContext;
import org.openspaces.persistency.cassandra.datasource.DataStaxIterator;
import org.openspaces.persistency.cassandra.error.SpaceCassandraDataSourceException;
import org.openspaces.persistency.cassandra.error.SpaceCassandraSchemaUpdateException;
import org.openspaces.persistency.cassandra.meta.*;
import org.openspaces.persistency.cassandra.meta.data.ColumnFamilyRow;
import org.openspaces.persistency.cassandra.meta.mapping.SpaceDocumentColumnFamilyMapper;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A wrapper around the DataStax Java Driver client library.
 *
 * @since 11.0.0
 * @author Bartosz Stalewski
 */
public class DataStaxCassandraClient implements CassandraClient {

    private static final int SLEEP_BEFORE_RETRY = 1000 * 11;

    private static final Log logger = LogFactory.getLog(DataStaxCassandraClient.class);

    private final NamedLockProvider namedLock = new NamedLockProvider();
    private final ColumnFamilyMetadataCache metadataCache = new ColumnFamilyMetadataCache();
    private final StatementCreator statementCreator;

    private final Cluster cluster;
    private final Session session;
    private final String keyspaceName;
    private final String hosts;
    private final Integer columnFamilyGcGraceSeconds;

    private final CassandraConsistencyLevel readConsistencyLevel;
    private final CassandraConsistencyLevel writeConsistencyLevel;
    private final CassandraConsistencyLevel metadataConsistencyLevel;

    private final Object lock = new Object();
    private boolean closed = false;

    private static final Integer DEFAULT_GC_GRACE_SECONDS = 86400;
    private static final String DEFAULT_CLUSTER_NAME = "cluster";
    private static final CassandraConsistencyLevel DEFAULT_CONSISTENCY_LEVEL = CassandraConsistencyLevel.QUORUM;
    private static final Integer DEFAULT_MAX_CONNECTIONS_LOCAL = 800;
    private static final Integer DEFAULT_MAX_CONNECTIONS_REMOTE = 200;
    private static final Integer DEFAULT_CONNECTIONS_FACTOR = DEFAULT_MAX_CONNECTIONS_LOCAL / DEFAULT_MAX_CONNECTIONS_REMOTE;

    public DataStaxCassandraClient(String clusterName, String keyspaceName,
                                   String hosts, Integer port, Integer columnFamilyGcGraceSeconds,
                                   ConsistencyLevel readConsistencyLevel, ConsistencyLevel writeConsistencyLevel) {
        this(clusterName, keyspaceName,
             hosts, port, columnFamilyGcGraceSeconds,
             readConsistencyLevel, writeConsistencyLevel,
             DEFAULT_MAX_CONNECTIONS_LOCAL, DEFAULT_MAX_CONNECTIONS_REMOTE);
    }

    public DataStaxCassandraClient(String clusterName, String keyspaceName,
                                   String hosts, Integer port, Integer columnFamilyGcGraceSeconds,
                                   ConsistencyLevel readConsistencyLevel, ConsistencyLevel writeConsistencyLevel,
                                   Integer maxLocalConnections, Integer maxRemoteConnections) {

        validateFields(keyspaceName, hosts, port, columnFamilyGcGraceSeconds, readConsistencyLevel);
        this.keyspaceName = keyspaceName;
        this.hosts = hosts;
        this.columnFamilyGcGraceSeconds = (columnFamilyGcGraceSeconds != null) ?
                columnFamilyGcGraceSeconds : DEFAULT_GC_GRACE_SECONDS;
        this.readConsistencyLevel = (readConsistencyLevel != null) ?
                DataStaxUtils.dataStaxConsistencyLevelToXap(readConsistencyLevel) : DEFAULT_CONSISTENCY_LEVEL;
        this.writeConsistencyLevel = (writeConsistencyLevel != null) ?
                DataStaxUtils.dataStaxConsistencyLevelToXap(writeConsistencyLevel) : DEFAULT_CONSISTENCY_LEVEL;
        this.metadataConsistencyLevel = DEFAULT_CONSISTENCY_LEVEL;
        this.statementCreator = new StatementCreator(this.readConsistencyLevel, this.writeConsistencyLevel);

        String actualClusterName = (clusterName != null) ? clusterName : DEFAULT_CLUSTER_NAME;

        this.cluster = Cluster.builder()
                .withClusterName(actualClusterName)
                .addContactPoints(hosts)
                .withPort(port)
                .withPoolingOptions(createPoolingOptions(maxLocalConnections, maxRemoteConnections))
                .build();

        // TODO: to retain exception consistency with previous version: catch exception and throw IllegalArgumentException
        this.session = cluster.connect(keyspaceName);
    }

    @Override
    public void close() {
        // TODO: check if locking is needed - session and cluster are probably thread-safe
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (session != null) {
                session.close();
            }
            if (cluster != null) {
                cluster.close();
            }
            closed = true;
        }
    }

    @Override
    public CassandraClient copyClientWithConnectionPoolSize(int minConnections, int maxConnections) {
        int port = cluster.getConfiguration().getProtocolOptions().getPort();
        return new DataStaxCassandraClient(cluster.getClusterName(), keyspaceName,
                hosts, port,
                columnFamilyGcGraceSeconds,
                DataStaxUtils.xapConsistencyLevelToDataStax(readConsistencyLevel),
                DataStaxUtils.xapConsistencyLevelToDataStax(writeConsistencyLevel),
                maxConnections * DEFAULT_CONNECTIONS_FACTOR,
                maxConnections);
    }

    @Override
    public CassandraConsistencyLevel getReadConsistencyLevel() {
        return readConsistencyLevel;
    }

    @Override
    public CassandraConsistencyLevel getWriteConsistencyLevel() {
        return writeConsistencyLevel;
    }

    @Override
    public void createMetadataColumnFamilyColumnFamilyIfNecessary() {
        createColumnFamilyIfNecessary(ColumnFamilyMetadataMetadata.INSTANCE, true);
    }

    @Override
    public void createColumnFamilyIfNecessary(ColumnFamilyMetadata metadata, boolean shouldPersistMetadata) {
        ReentrantLock lockForType = namedLock.forName(metadata.getTypeName());
        lockForType.lock();

        try {
            if (!doColumnFamilyExist(metadata.getColumnFamilyName())) {
                try {
                    createColumnFamily(metadata);
                    addIndexes(metadata);
                } catch (DriverException exception) {
                    if (logger.isInfoEnabled()) {
                        String template = "Column family creation failed, waiting %d seconds and then testing" +
                                " to see whether the column family was already created.";
                        // TODO: decide if exception should be printed (if happens regularly, then not)
                        //logger.info(String.format(template, (SLEEP_BEFORE_RETRY / 1000)), exception);
                        logger.info(String.format(template, (SLEEP_BEFORE_RETRY / 1000)));
                    }
                    Thread.sleep(SLEEP_BEFORE_RETRY);
                    if (!doColumnFamilyExist(metadata.getColumnFamilyName())) {
                        throw exception;
                    }
                }
            }

            if (metadata != ColumnFamilyMetadataMetadata.INSTANCE) {
                metadataCache.addColumnFamilyMetadata(metadata.getTypeName(), metadata);
                if (shouldPersistMetadata) {
                    persistColumnFamilyMetadata(metadata);
                }
            }
        } catch (Exception e) {
            throw new SpaceCassandraSchemaUpdateException("Failed adding column family definition to cassandra", e, true);
        } finally {
            lockForType.unlock();
        }
    }

    @Override
    public ColumnFamilyMetadata getColumnFamilyMetadata(String typeName) {
        return metadataCache.getColumnFamilyMetadata(typeName);
    }

    @Override
    public ColumnFamilyMetadata fetchColumnFamilyMetadata(String typeName, SpaceDocumentColumnFamilyMapper mapper) {
        String[] keys = {Metadata.quote(ColumnFamilyMetadataMetadata.KEY_NAME)};
        Object[] values = {typeName};
        ResultSet metadataResultSet = readValues(ColumnFamilyMetadataMetadata.INSTANCE, keys, values);

        Row metadataRow = metadataResultSet.one();
        ColumnFamilyMetadata metadata =
                (ColumnFamilyMetadata) metadataRow.getObject(ColumnFamilyMetadataMetadata.BLOB_COLUMN_NAME);
        metadataCache.addColumnFamilyMetadata(metadata.getTypeName(), metadata);

        return metadata;
    }

    @Override
    public Iterator<ColumnFamilyRow> readDocumentByQuery(ColumnFamilyMetadata metadata, CQLQueryContext queryContext, Object lastToken, Integer limit) {
        // TODO: rewrite to share logic for different types of queryContexts

        if (queryContext == null ) {
            ResultSet result = readValues(metadata, new String[0], new Object[0], lastToken, limit);
            return new DataStaxIterator(result.iterator(), metadata);
        } else if (StringUtils.hasText(queryContext.getSqlQuery())) {
            // TODO: implement handling queries with SQL Query
        } else if (queryContext.hasProperties()) {
            List<String> keys = new ArrayList<String>();
            List<Object> values = new ArrayList<Object>();
            for (Map.Entry<String, Object> entry : queryContext.getProperties().entrySet()) {
                if (entry.getValue() != null) {
                    keys.add(entry.getKey());
                    values.add(entry.getValue());
                }
            }

            ResultSet result = readValues(metadata, keys.toArray(new String[keys.size()]), values.toArray(), lastToken, limit);

            return new DataStaxIterator(result.iterator(), metadata);
        } else {
            // TODO: throw proper exception
            throw new IllegalArgumentException();
        }

        return null;
    }

    @Override
    public SpaceDocument readDocmentByKey(SpaceDocumentColumnFamilyMapper mapper,
                                            String typeName, Object keyValue) {
        ColumnFamilyMetadata metadata = metadataCache.getColumnFamilyMetadata(typeName);
        if (metadata == null) {
            metadata = fetchColumnFamilyMetadata(typeName, mapper);

            if (metadata == null) {
                return null;
            }
        }

        if (!doColumnFamilyExist(metadata.getColumnFamilyName())) {
            return null;
        }

        try {
            ResultSet result = readValues(metadata, new String[]{metadata.getKeyName()}, new Object[]{keyValue});
            Row row = result.one();
            ColumnFamilyRow columnFamilyRow = DataStaxUtils.mapToColumnFamilyRow(row, metadata);
            return mapper.toDocument(columnFamilyRow);
        } catch (Exception e) {
            // entry may have been removed in the meantime
            return null;
        }
    }

    @Override
    public Map<Object, SpaceDocument> readDocumentsByKeys(SpaceDocumentColumnFamilyMapper mapper,
                                                            String typeName, Object[] keyValues) {
        // TODO: implementation similar to readDocumentByKey, but IN clause should be used
        throw new NotImplementedException();
    }

    @Override
    public Map<String,ColumnFamilyMetadata> getColumnFamiliesMetadata() {
        return metadataCache.getColumnFamiliesMetadata();
    }

    @Override
    public Map<String,ColumnFamilyMetadata> populateColumnFamiliesMetadata(SpaceDocumentColumnFamilyMapper mapper) {
        ResultSet metadataResultSet = readAllValues(ColumnFamilyMetadataMetadata.INSTANCE);

        for (Row metadataRow: metadataResultSet) {
            ByteBuffer serializedMetadata = (ByteBuffer) metadataRow.getObject(ColumnFamilyMetadataMetadata.BLOB_COLUMN_NAME);
            ColumnFamilyMetadata metadata = (ColumnFamilyMetadata) DataStaxUtils.byteBufferToObject(serializedMetadata);
            metadataCache.addColumnFamilyMetadata(metadata.getTypeName(), metadata);
        }

        return metadataCache.getColumnFamiliesMetadata();
    }

    @Override
    public void addIndexesToColumnFamily(String typeName, List<String> columnNames,
                                         SpaceDocumentColumnFamilyMapper mapper) {
        // TODO: missing implementation
        throw new NotImplementedException();
    }

    @Override
    public void performBatchOperation(List<ColumnFamilyRow> rows) {
        Batch batchStatement = QueryBuilder.batch();
        for (ColumnFamilyRow columnFamilyRow: rows) {
            List<RegularStatement> statements = mapColumnFamilyRowToStatements(columnFamilyRow);
            for (RegularStatement subStatement: statements) {
                batchStatement.add(subStatement);
            }
        }

        try {
            session.execute(batchStatement);
        } catch (Exception e) {
            String msg = String.format("Exception during batch operation for query %s", batchStatement.getQueryString());
            throw new SpaceCassandraDataSourceException(msg, e);
        }
    }

    private List<RegularStatement> mapColumnFamilyRowToStatements(ColumnFamilyRow columnFamilyRow) {
        List<RegularStatement> statements = new ArrayList<RegularStatement>();
        switch (columnFamilyRow.getRowType()) {
            case Update:
                statements.addAll(statementCreator.createBatchDeleteStatement(columnFamilyRow)); // add possibility to
            case PartialUpdate:
            case Write:
                statements.addAll(statementCreator.createBatchInsertStatement(columnFamilyRow));
                break;
            case Remove:
                statements.addAll(statementCreator.createBatchDeleteStatement(columnFamilyRow));
            default:
                throw new IllegalStateException("should not have gotten here, got: " + columnFamilyRow.getRowType());
        }
        return statements;
    }

    private void validateFields(String keyspaceName, String hosts, Integer port, Integer columnFamilyGcGraceSeconds,
                                ConsistencyLevel readConsistencyLevel) {
        if (!StringUtils.hasText(keyspaceName)) {
            throw new IllegalArgumentException("keyspaceName must be set and non-empty");
        }

        if (hosts == null) {
            throw new IllegalArgumentException("hosts must be set");
        }

        if (!StringUtils.hasText(hosts.replace(",", ""))) {
            throw new IllegalArgumentException ("hosts must be non-empty");
        }

        if (port != null && port <= 0) {
            throw new IllegalArgumentException("port must be positive number");
        }

        if (columnFamilyGcGraceSeconds != null && columnFamilyGcGraceSeconds < 0) {
            throw new IllegalArgumentException("columnFamilyGcGraceSeconds must be non-negative");
        }

        if (readConsistencyLevel == ConsistencyLevel.ANY) {
            String msg = "%s consistency is not supported as read consistency level";
            throw new IllegalArgumentException(String.format(msg, readConsistencyLevel));
        }
    }

    private boolean doColumnFamilyExist(String columnFamilyName) {
        String keyspaceName = getKeyspaceName();
        return this.cluster.getMetadata().getKeyspace(keyspaceName).getTable(Metadata.quote(columnFamilyName)) != null;
    }

    private boolean isRepresentingSpaceDocument(ColumnFamilyMetadata metadata) {
        return metadata.getTypeDescriptorData() != null &&
            metadata.getTypeDescriptorData().getTypeDescriptor().supportsDynamicProperties();
    }

    private void createColumnFamily(ColumnFamilyMetadata metadata) {
        String columnFamilyName = metadata.getColumnFamilyName();
        DataType keyType = DataStaxUtils.classToDataStaxType(metadata.getKeyType());

        Create createTableStatement = SchemaBuilder
                .createTable(Metadata.quote(columnFamilyName))
                .addPartitionKey(Metadata.quote(metadata.getKeyName()), keyType);

        if (isRepresentingSpaceDocument(metadata)) {
            createTableStatement.addClusteringColumn(Metadata.quote(DataStaxUtils.PROPERTY_COLUMN_NAME), DataType.text());
            createTableStatement.addColumn(Metadata.quote(DataStaxUtils.PROPERTY_COLUMN_VALUE), DataType.blob());
        } else {
            for (TypedColumnMetadata column : metadata.getColumns().values()) {
                DataType columnType = DataStaxUtils.classToDataStaxType(column.getType());
                createTableStatement.addColumn(Metadata.quote(column.getFullName()), columnType);
            }
        }

        for (String index : metadata.getIndexes()) {
            if (!metadata.getColumns().containsKey(index)) {
                createTableStatement.addColumn(Metadata.quote(index), DataType.blob());
            }
        }

        Statement statementWithOptions = createTableStatement.withOptions()
                .gcGraceSeconds(columnFamilyGcGraceSeconds)
                .setConsistencyLevel(DataStaxUtils.xapConsistencyLevelToDataStax(metadataConsistencyLevel));

        session.execute(statementWithOptions);
    }

    private void addIndexes(ColumnFamilyMetadata metadata) {
        for (String indexColumn: metadata.getIndexes()) {
            String fullName = (metadata.getColumns().containsKey(indexColumn)) ?
                    metadata.getColumns().get(indexColumn).getFullName() : indexColumn;
            String indexName = generateIndexName(metadata.getTypeName(), fullName);
            SchemaStatement addIndexStatement = SchemaBuilder
                    .createIndex(indexName)
                    .ifNotExists()
                    .onTable(Metadata.quote(metadata.getColumnFamilyName()))
                    .andColumn(indexColumn);

            session.execute(addIndexStatement);
        }
    }

    private ResultSet writeValues(ColumnFamilyMetadata metadata, String[] rawKeys, Object[] rawValues) {
        Statement insertStatement = statementCreator.createInsertStatement(metadata, rawKeys, rawValues);
        return session.execute(insertStatement);
    }

    private void persistColumnFamilyMetadata(ColumnFamilyMetadata metadata) {
        String[] rawKeys = new String[]{ColumnFamilyMetadataMetadata.KEY_NAME, ColumnFamilyMetadataMetadata.BLOB_COLUMN_NAME};
        Object[] rawValues = new Object[]{metadata.getTypeName(), metadata};
        writeValues(ColumnFamilyMetadataMetadata.INSTANCE, rawKeys, rawValues);
    }

    // TODO: move logic of preparing statements to StatementCreator (similar to StatementCreator)
    private ResultSet readAllValues(ColumnFamilyMetadata metadata) {
        return readValues(metadata, new String[0], new Object[0]);
    }

    private ResultSet readValues(ColumnFamilyMetadata metadata, String[] whereKeys, Object[] whereValues) {
        return readValues(metadata, whereKeys, whereValues, null, null);
    }

    private ResultSet readValues(ColumnFamilyMetadata metadata, String[] whereKeys, Object[] whereValues, Object lastToken, Integer limit) {
        if (whereKeys.length != whereValues.length) {
            throw new IllegalArgumentException("Number of passed query keys does not match query values");
        }

        Select selectStatement = QueryBuilder
                .select()
                .all()
                .from(Metadata.quote(metadata.getColumnFamilyName()));

        if (limit != null) {
            selectStatement = selectStatement.limit(limit);
        }

        Select.Where selectWhereStatement = selectStatement.where();

        for (int i = 0; i < whereKeys.length; i++) {
            String whereKey = Metadata.quote(whereKeys[i]);
            selectWhereStatement = selectWhereStatement.and(QueryBuilder.eq(whereKey, whereValues[i]));
        }

        if (lastToken != null) {
            String keyName = Metadata.quote(metadata.getKeyName());
            selectWhereStatement = selectWhereStatement.and(QueryBuilder.lt(keyName, lastToken));
        }

        Statement statementWithOptions = selectWhereStatement
                .setConsistencyLevel(DataStaxUtils.xapConsistencyLevelToDataStax(readConsistencyLevel));

        return session.execute(statementWithOptions);
    }

    private String getKeyspaceName() {
        return keyspaceName;
    }

    private String generateIndexName(String typeName, String columnName) {
        return (typeName + "_" + columnName).replace(".", "_");
    }

    private PoolingOptions createPoolingOptions(Integer maxLocalConnections, Integer maxRemoteConnections) {
        // TODO: works only for protocol version 3 (C* 2.1), does not work for protocol version 2(C* 2.0) or 1(C* 1.2)
        // TODO: add field to client telling which protocol version should be used
        return new PoolingOptions()
                .setMaxRequestsPerConnection(HostDistance.LOCAL, maxLocalConnections)
                .setMaxRequestsPerConnection(HostDistance.REMOTE, maxRemoteConnections);
    }
}
