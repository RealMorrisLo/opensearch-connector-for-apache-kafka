FROM quay.io/strimzi/kafka:0.29.0-kafka-3.2.0
USER root:root
RUN mkdir -p /opt/kafka/plugins/debezium
RUN mkdir -p /opt/kafka/plugins/opensearch
COPY ./kafkaplugins/debezium-connector-postgres /opt/kafka/plugins/debezium/
COPY ./kafkaplugins/opensearch-connector-for-apache-kafka /opt/kafka/plugins/opensearch/
COPY ./kafkaplugins/confluentinc-kafka-connect-avro-converter-7.1.1 /opt/kafka/plugins/avro/
#COPY build/distributions/opensearch-connector-for-apache-kafka-1.1.0-SNAPSHOT.zip $KAFKA_CONNECT_PLUGINS_DIR/opensearch-connector/opensearch-connector.zip
#COPY kafkaplugins/confluentinc-kafka-connect-avro-converter-7.1.1.zip $KAFKA_CONNECT_PLUGINS_DIR/avro-converter/confluentinc-kafka-connect-avro-converter.zip
#RUN confluent-hub install --no-prompt /tmp/opensearch-connector.zip

USER 1001
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-jdbc:10.5.0
#RUN confluent-hub install --no-prompt debezium/debezium-connector-postgresql:1.9.2
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-aws-redshift:1.2.0
#RUN confluent-hub install --no-prompt confluentinc/connect-transforms:1.4.2
#RUN confluent-hub install --no-prompt an0r0c/kafka-connect-transform-record2jsonstring:1.0
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-avro-converter:7.1.1
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-protobuf-converter:7.1.1
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-json-schema-converter:7.1.1
#RUN confluent-hub install --no-prompt jcustenborder/kafka-config-provider-aws:0.1.1
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-kinesis:1.3.10
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-rabbitmq-sink:1.7.3
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-aws-lambda:2.0.2
#RUN confluent-hub install --no-prompt jcustenborder/kafka-connect-redis:0.0.2.17
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-http:1.5.3
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-aws-cloudwatch-metrics:1.1.7
#RUN confluent-hub install --no-prompt norsktipping/kafka-connect-jdbc_flatten:5.5.1
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-sqs:1.2.2
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-s3-source:2.1.1
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-rabbitmq:1.7.3
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-aws-cloudwatch-logs:1.2.1
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-replicator:7.1.1
#RUN confluent-hub install --no-prompt confluentinc/kafka-connect-influxdb:1.2.3
#RUN confluent-hub install --no-prompt debezium/debezium-connector-mongodb:1.9.2
#RUN confluent-hub install --no-prompt mongodb/kafka-connect-mongodb:1.7.0

ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["start"]
