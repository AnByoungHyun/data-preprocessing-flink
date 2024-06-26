package com.amazonaws.services.msf;

import com.amazonaws.services.kinesisanalytics.runtime.KinesisAnalyticsRuntime;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.connector.kinesis.sink.KinesisStreamsSink;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.LocalStreamEnvironment;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kinesis.FlinkKinesisConsumer;
import org.apache.flink.streaming.connectors.kinesis.config.AWSConfigConstants;
import org.apache.flink.streaming.connectors.kinesis.config.ConsumerConfigConstants;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.util.Collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.apache.flink.streaming.connectors.kinesis.config.ConsumerConfigConstants.*;
import static org.apache.flink.streaming.connectors.kinesis.config.ConsumerConfigConstants.RecordPublisherType.EFO;


public class StreamingJob {
    private static final Logger LOG = LoggerFactory.getLogger(StreamingJob.class);

    private static final String DEFAULT_SOURCE_STREAM = "source";
    private static final String DEFAULT_PUBLISHER_TYPE = RecordPublisherType.POLLING.name(); // "POLLING" for standard consumer, "EFO" for Enhanced Fan-Out
    private static final String DEFAULT_EFO_CONSUMER_NAME = "sample-efo-flink-consumer";
    private static final String DEFAULT_SINK_STREAM = "destination";
    private static final String DEFAULT_AWS_REGION = "eu-west-1";
    
    private static final ObjectMapper jsonParser = new ObjectMapper();

    /**
     * Get configuration properties from Amazon Managed Service for Apache Flink runtime properties
     * GroupID "FlinkApplicationProperties", or from command line parameters when running locally
     */
    private static ParameterTool loadApplicationParameters(String[] args, StreamExecutionEnvironment env) throws IOException {
        if (env instanceof LocalStreamEnvironment) {
            return ParameterTool.fromArgs(args);
        } else {
            Map<String, Properties> applicationProperties = KinesisAnalyticsRuntime.getApplicationProperties();
            Properties flinkProperties = applicationProperties.get("FlinkApplicationProperties");
            if (flinkProperties == null) {
                throw new RuntimeException("Unable to load FlinkApplicationProperties properties from runtime properties");
            }
            Map<String, String> map = new HashMap<>(flinkProperties.size());
            flinkProperties.forEach((k, v) -> map.put((String) k, (String) v));
            return ParameterTool.fromMap(map);
        }
    }

    private static FlinkKinesisConsumer<String> createKinesisSource(
            ParameterTool applicationProperties) {

        // Properties for Amazon Kinesis Data Streams Source, we need to specify from where we want to consume the data.
        // STREAM_INITIAL_POSITION: LATEST: consume messages that have arrived from the moment application has been deployed
        // STREAM_INITIAL_POSITION: TRIM_HORIZON: consume messages starting from first available in the Kinesis Stream
        Properties kinesisConsumerConfig = new Properties();
        kinesisConsumerConfig.put(AWSConfigConstants.AWS_REGION, applicationProperties.get("kinesis.region", DEFAULT_AWS_REGION));
        kinesisConsumerConfig.put(STREAM_INITIAL_POSITION, "TRIM_HORIZON");


        // Set up publisher type: POLLING (standard consumer) or EFO (Enhanced Fan-Out)
        kinesisConsumerConfig.put(RECORD_PUBLISHER_TYPE, applicationProperties.get("kinesis.source.type", DEFAULT_PUBLISHER_TYPE));
        if (kinesisConsumerConfig.getProperty(RECORD_PUBLISHER_TYPE).equals(EFO.name())) {
            kinesisConsumerConfig.put(ConsumerConfigConstants.EFO_CONSUMER_NAME, applicationProperties.get("kinesis.source.efoConsumer", DEFAULT_EFO_CONSUMER_NAME));
        }


        return new FlinkKinesisConsumer<>(applicationProperties.get("kinesis.source.stream", DEFAULT_SOURCE_STREAM), new SimpleStringSchema(), kinesisConsumerConfig);
    }

    private static KinesisStreamsSink<String> createKinesisSink(
            ParameterTool applicationProperties) {

        Properties sinkProperties = new Properties();
        // Required
        sinkProperties.put(AWSConfigConstants.AWS_REGION, applicationProperties.get("kinesis.region", DEFAULT_AWS_REGION));

        return KinesisStreamsSink.<String>builder()
                .setKinesisClientProperties(sinkProperties)
                .setSerializationSchema(new SimpleStringSchema())
                .setPartitionKeyGenerator(element -> String.valueOf(element.hashCode()))
                .setStreamName(applicationProperties.get("kinesis.sink.stream", DEFAULT_SINK_STREAM))
                .build();
    }
    
    private static DataStream<String> parseAndAggregate(DataStream<String> input) {
        // 상태 코드 추출 및 집계
        DataStream<Tuple2<String, Integer>> aggregated = input
                .flatMap(new FlatMapFunction<String, Tuple2<String, Integer>>() {
                    @Override
                    public void flatMap(String value, Collector<Tuple2<String, Integer>> out) throws Exception {
                        JsonNode jsonNode = jsonParser.readTree(value);
                        String statusCode = jsonNode.get("response").asText();
                        out.collect(new Tuple2<>(statusCode, 1));
                    }
                });

        // 상태 코드 및 카운트를 JSON 형식으로 변환
        return aggregated.map(new MapFunction<Tuple2<String, Integer>, String>() {
            @Override
            public String map(Tuple2<String, Integer> value) throws Exception {
                return "{\"status_code\":\"" + value.f0 + "\", \"count\":" + value.f1 + "}";
            }
        });
    }

    public static void main(String[] args) throws Exception {
        // set up the streaming execution environment
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        final ParameterTool applicationProperties = loadApplicationParameters(args, env);
        LOG.warn("Application properties: {}", applicationProperties.toMap());

        FlinkKinesisConsumer<String> source = createKinesisSource(applicationProperties);
        DataStream<String> input = env.addSource(source, "Kinesis source");
        
        DataStream<String> statusCounts = parseAndAggregate(input);

        KinesisStreamsSink<String> sink = createKinesisSink(applicationProperties);
        //input.sinkTo(sink);
        statusCounts.sinkTo(sink);

        env.execute("Flink Kinesis Source and Sink Version 1.2");
    }
}
