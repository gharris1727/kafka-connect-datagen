/**
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.kafka.connect.datagen;

import java.io.IOException;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import io.confluent.avro.random.generator.Generator;
import io.confluent.connect.avro.AvroData;
import org.apache.avro.generic.GenericData.Record;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatagenTask extends SourceTask {

  static final Logger log = LoggerFactory.getLogger(DatagenTask.class);

  private static final Schema KEY_SCHEMA = Schema.STRING_SCHEMA;
  public static final String TASK_ID = "task.id";
  public static final String TASK_GENERATION = "task.generation";
  public static final String CURRENT_ITERATION = "current.iteration";
  public static final String RANDOM_SEED = "random.seed";


  private DatagenConnectorConfig config;
  private String topic;
  private long maxInterval;
  private int maxRecords;
  private long count = 0L;
  private String schemaFilename;
  private String schemaKeyField;
  private Quickstart quickstart;
  private Generator generator;
  private org.apache.avro.Schema avroSchema;
  private org.apache.kafka.connect.data.Schema ksqlSchema;
  private AvroData avroData;
  private int taskId;
  private Map<String, Object> sourcePartition;
  private int taskGeneration;
  private Random random;

  protected enum Quickstart {
    CLICKSTREAM_CODES("clickstream_codes_schema.avro", "code"),
    CLICKSTREAM("clickstream_schema.avro", "ip"),
    CLICKSTREAM_USERS("clickstream_users_schema.avro", "user_id"),
    ORDERS("orders_schema.avro", "orderid"),
    RATINGS("ratings_schema.avro", "rating_id"),
    USERS("users_schema.avro", "userid"),
    USERS_("users_array_map_schema.avro", "userid"),
    PAGEVIEWS("pageviews_schema.avro", "viewtime"),
    STOCK_TRADES("stock_trades_schema.avro", "symbol");

    private final String schemaFilename;
    private final String keyName;

    Quickstart(String schemaFilename, String keyName) {
      this.schemaFilename = schemaFilename;
      this.keyName = keyName;
    }

    public String getSchemaFilename() {
      return schemaFilename;
    }

    public String getSchemaKeyField() {
      return keyName;
    }
  }

  @Override
  public String version() {
    return VersionUtil.getVersion();
  }

  @Override
  public void start(Map<String, String> props) {
    config = new DatagenConnectorConfig(props);
    topic = config.getKafkaTopic();
    maxInterval = config.getMaxInterval();
    maxRecords = config.getIterations();
    schemaFilename = config.getSchemaFilename();
    schemaKeyField = config.getSchemaKeyfield();
    taskId = Integer.parseInt(props.get(TASK_ID));
    sourcePartition = Collections.singletonMap(TASK_ID, taskId);

    random = new Random();
    if (config.getRandomSeed() != null) {
      random.setSeed(config.getRandomSeed());
      // Each task will now deterministically advance it's random source
      // This makes it such that each task will generate different data
      for (int i = 0; i < taskId; i++) {
        random.setSeed(random.nextLong());
      }
    }

    Map<String, Object> offset = context.offsetStorageReader().offset(sourcePartition);
    if (offset != null) {
      //  The offset as it is stored contains our next state, so restore it as-is.
      taskGeneration = ((Integer) offset.get(TASK_GENERATION));
      count = ((Long) offset.get(CURRENT_ITERATION));
      random.setSeed((Long) offset.get(RANDOM_SEED));
    }

    String quickstartName = config.getQuickstart();
    if (quickstartName != "") {
      try {
        quickstart = Quickstart.valueOf(quickstartName.toUpperCase());
        if (quickstart != null) {
          schemaFilename = quickstart.getSchemaFilename();
          schemaKeyField = quickstart.getSchemaKeyField();
          try {
            generator = new Generator(
                getClass().getClassLoader().getResourceAsStream(schemaFilename),
                random,
                count
            );
          } catch (IOException e) {
            throw new ConnectException("Unable to read the '"
                + schemaFilename + "' schema file", e);
          }
        }
      } catch (IllegalArgumentException e) {
        log.warn("Quickstart '{}' not found: ", quickstartName, e);
      }
    } else {
      try {
        generator = new Generator(
            new FileInputStream(schemaFilename),
            random,
            count
        );
      } catch (IOException e) {
        throw new ConnectException("Unable to read the '"
            + schemaFilename + "' schema file", e);
      }
    }

    avroSchema = generator.schema();
    avroData = new AvroData(1);
    ksqlSchema = avroData.toConnectSchema(avroSchema);
  }

  @Override
  public List<SourceRecord> poll() throws InterruptedException {

    if (maxInterval > 0) {
      try {
        Thread.sleep((long) (maxInterval * Math.random()));
      } catch (InterruptedException e) {
        Thread.interrupted();
        return null;
      }
    }

    final Object generatedObject = generator.generate();
    if (!(generatedObject instanceof GenericRecord)) {
      throw new RuntimeException(String.format(
          "Expected Avro Random Generator to return instance of GenericRecord, found %s instead",
          generatedObject.getClass().getName()
      ));
    }
    final GenericRecord randomAvroMessage = (GenericRecord) generatedObject;

    final List<Object> genericRowValues = new ArrayList<>();
    for (org.apache.avro.Schema.Field field : avroSchema.getFields()) {
      final Object value = randomAvroMessage.get(field.name());
      if (value instanceof Record) {
        final Record record = (Record) value;
        final Object ksqlValue = avroData.toConnectData(record.getSchema(), record).value();
        Object optionValue = getOptionalValue(ksqlSchema.field(field.name()).schema(), ksqlValue);
        genericRowValues.add(optionValue);
      } else {
        genericRowValues.add(value);
      }
    }

    // Task ID field
    if (!config.getSchemaTaskIdField().isEmpty()) {
      randomAvroMessage.put(config.getSchemaTaskIdField(), taskId);
    }

    // Task Generation field
    if (!config.getSchemaTaskGenerationField().isEmpty()) {
      randomAvroMessage.put(config.getSchemaTaskGenerationField(), taskGeneration);
    }

    // Key
    String keyString = "";
    if (!schemaKeyField.isEmpty()) {
      SchemaAndValue schemaAndValue = avroData.toConnectData(
          randomAvroMessage.getSchema().getField(schemaKeyField).schema(),
          randomAvroMessage.get(schemaKeyField)
      );
      keyString = schemaAndValue.value().toString();
    }

    // Value
    final org.apache.kafka.connect.data.Schema messageSchema = avroData.toConnectSchema(avroSchema);
    final Object messageValue = avroData.toConnectData(avroSchema, randomAvroMessage).value();

    if (maxRecords > 0 && count >= maxRecords) {
      throw new ConnectException(
          String.format("Stopping connector: generated the configured %d number of messages", count)
      );
    }

    // Partition number
    Integer partition = null;
    if (!config.getSchemaPartitionField().isEmpty()) {
      Object p = randomAvroMessage.get(config.getSchemaPartitionField());
      if (!(p instanceof Integer)) {
        throw new ConnectException("Field specified in "
            + DatagenConnectorConfig.SCHEMA_PARTITION_FIELD_CONF + " must be an integer");
      }
      partition = (Integer) p;
    }

    // Re-seed the random each time so that we can save the seed to the source offsets.
    long seed = random.nextLong();
    random.setSeed(seed);

    // The source offsets will be the values that the next task lifetime will restore from
    // Essentially, the "next" state of the connector after this loop completes
    Map<String, Object> sourceOffset = new HashMap<>();
    // The next lifetime will be a member of the next generation.
    sourceOffset.put(TASK_GENERATION, taskGeneration + 1);
    // We will have produced this record
    sourceOffset.put(CURRENT_ITERATION, count + 1);
    // This is the seed that we just re-seeded for our own next iteration.
    sourceOffset.put(RANDOM_SEED, seed);

    final List<SourceRecord> records = new ArrayList<>();
    SourceRecord record = new SourceRecord(
        sourcePartition,
        sourceOffset,
        topic,
        partition,
        KEY_SCHEMA,
        keyString,
        messageSchema,
        messageValue
    );
    records.add(record);
    count += records.size();
    return records;
  }

  @Override
  public void stop() {
  }

  private org.apache.kafka.connect.data.Schema getOptionalSchema(
      final org.apache.kafka.connect.data.Schema schema
  ) {
    switch (schema.type()) {
      case BOOLEAN:
        return org.apache.kafka.connect.data.Schema.OPTIONAL_BOOLEAN_SCHEMA;
      case INT32:
        return org.apache.kafka.connect.data.Schema.OPTIONAL_INT32_SCHEMA;
      case INT64:
        return org.apache.kafka.connect.data.Schema.OPTIONAL_INT64_SCHEMA;
      case FLOAT64:
        return org.apache.kafka.connect.data.Schema.OPTIONAL_FLOAT64_SCHEMA;
      case STRING:
        return org.apache.kafka.connect.data.Schema.OPTIONAL_STRING_SCHEMA;
      case ARRAY:
        return SchemaBuilder.array(getOptionalSchema(schema.valueSchema())).optional().build();
      case MAP:
        return SchemaBuilder.map(
            getOptionalSchema(schema.keySchema()),
            getOptionalSchema(schema.valueSchema())
        ).optional().build();
      case STRUCT:
        final SchemaBuilder schemaBuilder = SchemaBuilder.struct();
        for (Field field : schema.fields()) {
          schemaBuilder.field(
              field.name(),
              getOptionalSchema(field.schema())
          );
        }
        return schemaBuilder.optional().build();
      default:
        throw new ConnectException("Unsupported type: " + schema);
    }
  }

  private Object getOptionalValue(
      final org.apache.kafka.connect.data.Schema schema,
      final Object value
  ) {
    switch (schema.type()) {
      case BOOLEAN:
      case INT32:
      case INT64:
      case FLOAT64:
      case STRING:
        return value;
      case ARRAY:
        final List<?> list = (List<?>) value;
        return list.stream()
                   .map(listItem -> getOptionalValue(schema.valueSchema(), listItem))
                   .collect(Collectors.toList());
      case MAP:
        final Map<?, ?> map = (Map<?, ?>) value;
        return map.entrySet().stream()
            .collect(Collectors.toMap(
                k -> getOptionalValue(schema.keySchema(), k),
                v -> getOptionalValue(schema.valueSchema(), v)
            ));
      case STRUCT:
        final Struct struct = (Struct) value;
        final Struct optionalStruct = new Struct(getOptionalSchema(schema));
        for (Field field : schema.fields()) {
          optionalStruct.put(
              field.name(),
              getOptionalValue(
                  field.schema(),
                  struct.get(field.name())
              )
          );
        }
        return optionalStruct;
      default:
        throw new ConnectException("Invalid value schema: " + schema + ", value = " + value);
    }
  }
}
