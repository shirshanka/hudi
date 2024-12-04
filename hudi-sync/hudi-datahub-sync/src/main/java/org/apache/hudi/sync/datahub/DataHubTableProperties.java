/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hudi.sync.datahub;

import org.apache.hudi.common.config.ConfigProperty;
import org.apache.hudi.common.model.HoodieFileFormat;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.util.ConfigUtils;
import org.apache.hudi.sync.common.util.SparkDataSourceTableUtils;
import org.apache.hudi.sync.datahub.config.DataHubSyncConfig;

import org.apache.parquet.schema.MessageType;

import java.util.HashMap;
import java.util.Map;

import static org.apache.hudi.hadoop.utils.HoodieInputFormatUtils.getInputFormatClassName;
import static org.apache.hudi.hadoop.utils.HoodieInputFormatUtils.getOutputFormatClassName;
import static org.apache.hudi.hadoop.utils.HoodieInputFormatUtils.getSerDeClassName;
import static org.apache.hudi.sync.datahub.config.DataHubSyncConfig.HIVE_SYNC_SCHEMA_STRING_LENGTH_THRESHOLD;
import static org.apache.hudi.sync.datahub.config.DataHubSyncConfig.HIVE_TABLE_SERDE_PROPERTIES;
import static org.apache.hudi.sync.datahub.config.DataHubSyncConfig.META_SYNC_BASE_FILE_FORMAT;
import static org.apache.hudi.sync.datahub.config.DataHubSyncConfig.META_SYNC_BASE_PATH;
import static org.apache.hudi.sync.datahub.config.DataHubSyncConfig.META_SYNC_PARTITION_FIELDS;
import static org.apache.hudi.sync.datahub.config.DataHubSyncConfig.META_SYNC_SPARK_VERSION;

/**
 * Implementation of table properties management for DataHub sync
 */
public class DataHubTableProperties {

  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DataHubTableProperties.class);

  public static final ConfigProperty<String> DATAHUB_TABLE_PROPERTIES =
      ConfigProperty.key("hoodie.meta.sync.datahub.table.properties")
          .defaultValue("")
          .withDocumentation(
              "Additional properties to be attached to the DataHub dataset, specified as key1=val1,key2=val2");

  private static Map<String, String> getSerdeProperties(DataHubSyncConfig config, boolean readAsOptimized) {
    HoodieFileFormat baseFileFormat =


        HoodieFileFormat.valueOf(config.getStringOrDefault(META_SYNC_BASE_FILE_FORMAT).toUpperCase());
    String inputFormatClassName = getInputFormatClassName(baseFileFormat, false, false);
    String outputFormatClassName = getOutputFormatClassName(baseFileFormat);
    String serDeFormatClassName = getSerDeClassName(baseFileFormat);

    Map<String, String> serdeProperties = ConfigUtils.toMap(config.getString(HIVE_TABLE_SERDE_PROPERTIES));
    serdeProperties.put("inputFormat", inputFormatClassName);
    serdeProperties.put("outputFormat", outputFormatClassName);
    serdeProperties.put("serdeClass", serDeFormatClassName);
    Map<String, String> sparkSerdeProperties =
        SparkDataSourceTableUtils.getSparkSerdeProperties(readAsOptimized, config.getString(META_SYNC_BASE_PATH));
    // Add the properties to the serde properties, but prefix the keys with "spark." if they are not already prefixed with "spark."
    sparkSerdeProperties.forEach((k, v) -> serdeProperties.putIfAbsent(k.startsWith("spark.") ? k : "spark." + k, v));
    LOG.info("Serde Properties : {}", serdeProperties);
    return serdeProperties;
  }

  /**
   * Get table properties for DataHub dataset
   *
   * @param config        DataHub sync configuration
   * @param tableMetadata Hoodie table metadata
   * @return Map of properties to be set in DataHub
   */
  public static Map<String, String> getTableProperties(DataHubSyncConfig config, HoodieTableMetadata tableMetadata) {
    Map<String, String> properties = new HashMap<>();

    // Add basic Hudi table properties
    properties.put("hudi.table.type", tableMetadata.getTableType());
    properties.put("hudi.table.version", tableMetadata.getTableVersion());
    properties.put("hudi.base.path", config.getString(META_SYNC_BASE_PATH));

    // Add partitioning information
    if (!config.getSplitStrings(META_SYNC_PARTITION_FIELDS).isEmpty()) {
      properties.put("hudi.partition.fields", String.join(",", config.getSplitStrings(META_SYNC_PARTITION_FIELDS)));
    }

    // Add user-defined properties
    Map<String, String> userDefinedProps = ConfigUtils.toMap(config.getString(DATAHUB_TABLE_PROPERTIES));
    properties.putAll(userDefinedProps);

    // Add Spark related properties

    Map<String, String> sparkProperties =
        SparkDataSourceTableUtils.getSparkTableProperties(config.getSplitStrings(META_SYNC_PARTITION_FIELDS),
            config.getString(META_SYNC_SPARK_VERSION), config.getInt(HIVE_SYNC_SCHEMA_STRING_LENGTH_THRESHOLD),
            tableMetadata.getSchema());

    properties.putAll(sparkProperties);
    properties.putAll(getSerdeProperties(config, false));

    return properties;
  }

  /**
   * Helper class to manage table metadata
   */
  public static class HoodieTableMetadata {
    private final HoodieTableMetaClient metaClient;
    private final MessageType schema;

    public HoodieTableMetadata(HoodieTableMetaClient metaClient, MessageType schema) {
      this.metaClient = metaClient;
      this.schema = schema;
    }

    public String getTableType() {
      return metaClient.getTableType().name();
    }

    public String getTableVersion() {
      return metaClient.getTableConfig().getTableVersion().toString();
    }

    public MessageType getSchema() {
      return schema;
    }
  }
}