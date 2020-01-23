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

import com.gigaspaces.sync.*;
import org.openspaces.persistency.cassandra.error.SpaceCassandraSynchronizationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * A Cassandra implementation of {@link SpaceSynchronizationEndpoint}.
 * 
 * @since 9.1.1
 * @author Dan Kilman
 */
public class CassandraSpaceSynchronizationEndpoint
        extends SpaceSynchronizationEndpoint {

    private static Logger logger = LoggerFactory.getLogger(CassandraSpaceSynchronizationEndpoint.class);
    @Override
    public void onTransactionSynchronization(TransactionData transactionData) {
        doSynchronization(transactionData.getTransactionParticipantDataItems());
    }

    @Override
    public void onOperationsBatchSynchronization(OperationsBatchData batchData) {
        doSynchronization(batchData.getBatchDataItems());
    }

    private void doSynchronization(DataSyncOperation[] dataSyncOperations) {
        logger.trace("Starting batch operation");

        for (DataSyncOperation dataSyncOperation : dataSyncOperations) {

            if (!dataSyncOperation.supportsDataAsDocument()) {
                throw new SpaceCassandraSynchronizationException("Data sync operation does not support asDocument", null);
            }

            /*

            SpaceDocument spaceDoc = dataSyncOperation.getDataAsDocument();
            String typeName = spaceDoc.getTypeName();
            ColumnFamilyMetadata metadata = hectorClient.getColumnFamilyMetadata(typeName);

            if (metadata == null) {
                metadata = hectorClient.fetchColumnFamilyMetadata(typeName, mapper);
                if (metadata == null) {
                    throw new SpaceCassandraDataSourceException("Could not find column family for type name: "
                            + typeName, null);
                }
            }

            String keyName = metadata.getKeyName();
            Object keyValue = spaceDoc.getProperty(keyName);

            if (keyValue == null) {
                throw new SpaceCassandraSynchronizationException("Data sync operation missing id property value", null);
            }

            ColumnFamilyRow columnFamilyRow;
            switch(dataSyncOperation.getDataSyncOperationType()) {
                case WRITE:
                    columnFamilyRow = mapper.toColumnFamilyRow(metadata,
                            spaceDoc,
                            ColumnFamilyRowType.Write,
                            true / * useDynamicPropertySerializerForDynamicColumns* /);
                    break;
                case UPDATE:
                    columnFamilyRow = mapper.toColumnFamilyRow(metadata,
                            spaceDoc,
                            ColumnFamilyRowType.Update,
                            true /* useDynamicPropertySerializerForDynamicColumns* /);
                    break;
                case PARTIAL_UPDATE:
                    columnFamilyRow = mapper.toColumnFamilyRow(metadata,
                            spaceDoc,
                            ColumnFamilyRowType.PartialUpdate,
                            true /* useDynamicPropertySerializerForDynamicColumns* /);
                    break;
                case REMOVE:
                    columnFamilyRow = new ColumnFamilyRow(metadata, keyValue, ColumnFamilyRowType.Remove);
                    break;
                default:
                {
                    throw new IllegalStateException("Unsupported data sync operation type: " +
                            dataSyncOperation.getDataSyncOperationType());
                }
            }

            if (logger.isTraceEnabled()) {
                logger.trace("Adding row: " + columnFamilyRow + " to current batch");
            }

            List<ColumnFamilyRow> rows = cfToRows.get(metadata.getColumnFamilyName());
            if (rows == null) {
                rows = new LinkedList<ColumnFamilyRow>();
                cfToRows.put(metadata.getColumnFamilyName(), rows);
            }
            rows.add(columnFamilyRow);

          */
        }

        logger.trace("Performing batch operation");

    }


    @Override
    public void onIntroduceType(IntroduceTypeData introduceTypeData) {
        
    }
    
    @Override
    public void onAddIndex(AddIndexData addIndexData) {
    }
    
}
