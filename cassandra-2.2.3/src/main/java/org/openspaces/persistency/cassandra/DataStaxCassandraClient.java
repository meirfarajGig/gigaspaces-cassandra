package org.openspaces.persistency.cassandra;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.Session;
import com.gigaspaces.document.SpaceDocument;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openspaces.persistency.cassandra.meta.ColumnFamilyMetadata;
import org.openspaces.persistency.cassandra.meta.data.ColumnFamilyRow;
import org.openspaces.persistency.cassandra.meta.mapping.SpaceDocumentColumnFamilyMapper;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * A wrapper around the DataStax Java Driver client library.
 *
 * @since 11.0.0
 * @author Bartosz Stalewski
 */
public class DataStaxCassandraClient implements CassandraClient {

    //private static final int SLEEP_BEFORE_RETRY = 1000 * 11;

    private static final Log logger = LogFactory.getLog(DataStaxCassandraClient.class);

    // cache

    private final Cluster cluster;
    private final Session session;
    private final String keyspaceName;
    private final Integer columnFamilyGcGraceSeconds;

    private final ConsistencyLevel readConsistencyLevel;
    private final ConsistencyLevel writeConsistencyLevel;
    private final ConsistencyLevel metadataConsistencyLevel;

    private final Object lock = new Object();
    private boolean closed = false;

    private static final Integer DEFAULT_GC_GRACE_SECONDS = 86400;
    private static final String DEFAULT_CLUSTER_NAME = "cluster";
    private static final ConsistencyLevel DEFAULT_CONSISTENCY_LEVEL = ConsistencyLevel.QUORUM;

    public DataStaxCassandraClient(String clusterName, String keyspaceName,
                                   String hosts, Integer port, Integer columnFamilyGcGraceSeconds,
                                   ConsistencyLevel readConsistencyLevel, ConsistencyLevel writeConsistencyLevel) {

        validateFields(keyspaceName, hosts, port, columnFamilyGcGraceSeconds, readConsistencyLevel);
        this.keyspaceName = keyspaceName;
        this.columnFamilyGcGraceSeconds = (columnFamilyGcGraceSeconds != null) ?
                columnFamilyGcGraceSeconds : DEFAULT_GC_GRACE_SECONDS;
        this.readConsistencyLevel = (readConsistencyLevel != null) ?
                readConsistencyLevel : DEFAULT_CONSISTENCY_LEVEL;
        this.writeConsistencyLevel = (writeConsistencyLevel != null) ?
                writeConsistencyLevel : DEFAULT_CONSISTENCY_LEVEL;
        this.metadataConsistencyLevel = DEFAULT_CONSISTENCY_LEVEL;

        String actualClusterName = (clusterName != null) ? clusterName : DEFAULT_CLUSTER_NAME;

        cluster = Cluster.builder()
                .withClusterName(actualClusterName)
                .addContactPoints(hosts)
                .withPort(port)
                .build();

        // TODO[maybe]: to retain exception consistency: catch exception and throw IllegalArgumentException
        session = cluster.connect(keyspaceName);
    }

    public void close() {
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

    public CassandraConsistencyLevel getReadConsistencyLevel() {
        return null;
    }

    public CassandraConsistencyLevel getWriteConsistencyLevel() {
        return null;
    }

    public void createMetadataColumnFamilyColumnFamilyIfNecessary() {

    }

    public ColumnFamilyMetadata getColumnFamilyMetadata(String typeName) {
        return null;
    }

    public ColumnFamilyMetadata fetchColumnFamilyMetadata(String typeName, SpaceDocumentColumnFamilyMapper mapper) {
        return null;
    }

    public SpaceDocument readDocmentByKey(SpaceDocumentColumnFamilyMapper mapper,
                                            String typeName, Object keyValue) {
        return null;
    }

    public Map<Object, SpaceDocument> readDocumentsByKeys(SpaceDocumentColumnFamilyMapper mapper,
                                                            String typeName, Object[] keyValues) {
        return null;
    }

    public Map<String,ColumnFamilyMetadata> getColumnFamiliesMetadata() {
        return null;
    }

    public Map<String,ColumnFamilyMetadata> populateColumnFamiliesMetadata(SpaceDocumentColumnFamilyMapper mapper) {
        return null;
    }

    public void createColumnFamilyIfNecessary(ColumnFamilyMetadata metadata, boolean shouldPersist) {
    }

    public void addIndexesToColumnFamily(String typeName, List<String> columnNames,
                                         SpaceDocumentColumnFamilyMapper mapper) {

    }

    public void performBatchOperation(List<ColumnFamilyRow> rows) {
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
}
