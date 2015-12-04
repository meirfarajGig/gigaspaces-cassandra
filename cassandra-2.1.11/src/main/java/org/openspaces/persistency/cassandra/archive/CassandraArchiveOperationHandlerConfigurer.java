package org.openspaces.persistency.cassandra.archive;

import org.openspaces.core.GigaSpace;
import org.openspaces.persistency.cassandra.CassandraClient;
import org.openspaces.persistency.cassandra.CassandraConsistencyLevel;

public class CassandraArchiveOperationHandlerConfigurer {

	CassandraArchiveOperationHandler handler;
	private boolean initialized;
	
	public CassandraArchiveOperationHandlerConfigurer() {
		handler = new CassandraArchiveOperationHandler();
	}

	/**
	 * @see CassandraArchiveOperationHandler#setGigaSpace(GigaSpace)
	 */
	public CassandraArchiveOperationHandlerConfigurer gigaSpace(GigaSpace gigaSpace) {
		handler.setGigaSpace(gigaSpace);
		return this;
	}

    /**
     * @see CassandraArchiveOperationHandler#setCassandraClient(String)
     */
    public CassandraArchiveOperationHandlerConfigurer cassandraClient(CassandraClient cassandraClient) {
        handler.setCassandraClient(cassandraClient);
        return this;
    }
	
	public CassandraArchiveOperationHandler create() {
		if (!initialized) {
			handler.afterPropertiesSet();
			initialized = true;
		}
		return handler;
	}
}
