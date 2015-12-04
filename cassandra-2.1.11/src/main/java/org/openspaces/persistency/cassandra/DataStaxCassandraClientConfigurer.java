package org.openspaces.persistency.cassandra;

import com.datastax.driver.core.ConsistencyLevel;

public class DataStaxCassandraClientConfigurer {

    protected String clusterName;
    protected String keyspaceName;
    protected String hosts;
    protected Integer port;
    protected Integer columnFamilyGcGraceSeconds;
    protected ConsistencyLevel readConsistencyLevel;
    protected ConsistencyLevel writeConsistencyLevel;

    public DataStaxCassandraClientConfigurer clusterName(String clusterName) {
        this.clusterName = clusterName;
        return this;
    }

    public DataStaxCassandraClientConfigurer keyspaceName(String keyspaceName) {
        this.keyspaceName = keyspaceName;
        return this;
    }

    public DataStaxCassandraClientConfigurer hosts(String hosts) {
        this.hosts = hosts;
        return this;
    }

    public DataStaxCassandraClientConfigurer port(Integer port) {
        this.port = port;
        return this;
    }

    public DataStaxCassandraClientConfigurer columnFamilyGcGraceSeconds(Integer columnFamilyGcGraceSeconds) {
        this.columnFamilyGcGraceSeconds = columnFamilyGcGraceSeconds;
        return this;
    }

    public DataStaxCassandraClientConfigurer readConsistencyLevel(CassandraConsistencyLevel readConsistencyLevel) {
        this.readConsistencyLevel = DataStaxUtils.xapConsistencyLevelToDataStax(readConsistencyLevel);
        return this;
    }

    public DataStaxCassandraClientConfigurer writeConsistencyLevel(CassandraConsistencyLevel writeConsistencyLevel) {
        this.writeConsistencyLevel = DataStaxUtils.xapConsistencyLevelToDataStax(writeConsistencyLevel);
        return this;
    }

    public DataStaxCassandraClient create() {
        return new DataStaxCassandraClient(clusterName, keyspaceName,
                hosts, port,
                columnFamilyGcGraceSeconds,
                readConsistencyLevel, writeConsistencyLevel);
    }
}
