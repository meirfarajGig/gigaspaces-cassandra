package org.openspaces.persistency.cassandra;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;

public class DataStaxCassandraClientFactoryBean implements
        FactoryBean<CassandraClient>, InitializingBean, DisposableBean {

    private final DataStaxCassandraClientConfigurer configurer = getConfigurer();

    protected DataStaxCassandraClientConfigurer getConfigurer() {
        return new DataStaxCassandraClientConfigurer();
    }

    private DataStaxCassandraClient cassandraClient;

    public void setClusterName(String clusterName) {
        configurer.clusterName(clusterName);
    }

    public void setKeyspaceName(String keyspaceName) {
        configurer.keyspaceName(keyspaceName);
    }

    public void setHosts(String hosts) {
        configurer.hosts(hosts);
    }

    public void setPort(Integer port) {
        configurer.port(port);
    }

    public void setColumnFamilyGcGraceSeconds(Integer columnFamilyGcGraceSeconds) {
        configurer.columnFamilyGcGraceSeconds(columnFamilyGcGraceSeconds);
    }

    public void setReadConsistencyLevel(CassandraConsistencyLevel readConsistencyLevel) {
        configurer.readConsistencyLevel(readConsistencyLevel);
    }

    public void setWriteConsistencyLevel(CassandraConsistencyLevel writeConsistencyLevel) {
        configurer.writeConsistencyLevel(writeConsistencyLevel);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        cassandraClient = configurer.create();
    }

    @Override
    //public DataStaxCassandraClient getObject() throws Exception {
    public CassandraClient getObject() throws Exception {
        return cassandraClient;
    }

    @Override
    public Class<?> getObjectType() {
        return DataStaxCassandraClient.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    @Override
    public void destroy() throws Exception {
        cassandraClient.close();
    }
}
