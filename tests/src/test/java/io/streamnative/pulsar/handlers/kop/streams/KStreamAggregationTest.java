/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.kop.streams;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NonNull;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Aggregator;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Initializer;
import org.apache.kafka.streams.kstream.KGroupedStream;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Reducer;
import org.apache.kafka.streams.kstream.Serialized;
import org.apache.kafka.streams.kstream.SessionWindowedDeserializer;
import org.apache.kafka.streams.kstream.SessionWindows;
import org.apache.kafka.streams.kstream.TimeWindowedDeserializer;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.WindowedSerdes;
import org.apache.kafka.streams.kstream.internals.SessionWindow;
import org.apache.kafka.streams.kstream.internals.TimeWindow;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlySessionStore;
import org.testng.annotations.Test;


/**
 * Tests for KStream aggregation.
 */
public class KStreamAggregationTest extends KafkaStreamsTestBase {

    private final AtomicInteger groupIdIndex = new AtomicInteger(0);

    private String streamOneInput;
    private String outputTopic;
    private String userSessionsStream;

    private KGroupedStream<String, String> groupedStream;
    private Reducer<String> reducer;
    private Initializer<Integer> initializer;
    private Aggregator<String, String, Integer> aggregator;
    private KStream<Integer, String> stream;

    @Override
    protected void createTopics() throws Exception {
        streamOneInput = "stream-one-" + getTestNo();
        outputTopic = "output-" + getTestNo();
        userSessionsStream = "user-sessions-" + getTestNo();
        admin.topics().createPartitionedTopic(streamOneInput, 3);
        admin.topics().createPartitionedTopic(userSessionsStream, 1);
        admin.topics().createPartitionedTopic(outputTopic, 1);
    }

    @Override
    protected @NonNull String getApplicationIdPrefix() {
        return "kgrouped-stream-test";
    }

    @Override
    protected void extraSetup() throws Exception {
        final KeyValueMapper<Integer, String, String> mapper = (key, value) -> value;
        stream = builder.stream(streamOneInput, Consumed.with(Serdes.Integer(), Serdes.String()));
        groupedStream = stream
                .groupBy(
                        mapper,
                        Serialized.with(Serdes.String(), Serdes.String()));

        reducer = (value1, value2) -> value1 + ":" + value2;
        initializer = () -> 0;
        aggregator = (aggKey, value, aggregate) -> aggregate + value.length();
    }

    @Override
    protected Class<?> getKeySerdeClass() {
        return Serdes.String().getClass();
    }

    @Override
    protected Class<?> getValueSerdeClass() {
        return Serdes.Integer().getClass();
    }

    @Test
    public void shouldReduce() throws Exception {
        produceMessages(mockTime.milliseconds());
        groupedStream
                .reduce(reducer, Materialized.<String, String, KeyValueStore<Bytes, byte[]>>as("reduce-by-key"))
                .toStream()
                .to(outputTopic, Produced.with(Serdes.String(), Serdes.String()));

        startStreams();

        produceMessages(mockTime.milliseconds());

        final List<KeyValue<String, String>> results = receiveMessages(
                new StringDeserializer(),
                new StringDeserializer(),
                10);

        results.sort(KStreamAggregationTest::compare);

        assertThat(results, is(Arrays.asList(KeyValue.pair("A", "A"),
                KeyValue.pair("A", "A:A"),
                KeyValue.pair("B", "B"),
                KeyValue.pair("B", "B:B"),
                KeyValue.pair("C", "C"),
                KeyValue.pair("C", "C:C"),
                KeyValue.pair("D", "D"),
                KeyValue.pair("D", "D:D"),
                KeyValue.pair("E", "E"),
                KeyValue.pair("E", "E:E"))));
    }

    @Test
    public void shouldReduceWindowed() throws Exception {
        final long firstBatchTimestamp = mockTime.milliseconds();
        mockTime.sleep(1000);
        produceMessages(firstBatchTimestamp);
        final long secondBatchTimestamp = mockTime.milliseconds();
        produceMessages(secondBatchTimestamp);
        produceMessages(secondBatchTimestamp);

        final Serde<Windowed<String>> windowedSerde = WindowedSerdes.timeWindowedSerdeFrom(String.class);
        groupedStream
                .windowedBy(TimeWindows.of(500L))
                .reduce(reducer)
                .toStream()
                .to(outputTopic, Produced.with(windowedSerde, Serdes.String()));

        startStreams();

        final List<KeyValue<Windowed<String>, String>> windowedOutput = receiveMessages(
                new TimeWindowedDeserializer<>(),
                new StringDeserializer(),
                String.class,
                15);

        // read from ConsoleConsumer
        final String resultFromConsoleConsumer = readWindowedKeyedMessagesViaConsoleConsumer(
                new TimeWindowedDeserializer<String>(),
                new StringDeserializer(),
                String.class,
                15,
                false);

        final Comparator<KeyValue<Windowed<String>, String>>
                comparator =
                Comparator.comparing((KeyValue<Windowed<String>, String> o) -> o.key.key()).thenComparing(o -> o.value);

        windowedOutput.sort(comparator);
        final long firstBatchWindow = firstBatchTimestamp / 500 * 500;
        final long secondBatchWindow = secondBatchTimestamp / 500 * 500;

        final List<KeyValue<Windowed<String>, String>> expectResult = Arrays.asList(
                new KeyValue<>(new Windowed<>("A", new TimeWindow(firstBatchWindow, Long.MAX_VALUE)), "A"),
                new KeyValue<>(new Windowed<>("A", new TimeWindow(secondBatchWindow, Long.MAX_VALUE)), "A"),
                new KeyValue<>(new Windowed<>("A", new TimeWindow(secondBatchWindow, Long.MAX_VALUE)), "A:A"),
                new KeyValue<>(new Windowed<>("B", new TimeWindow(firstBatchWindow, Long.MAX_VALUE)), "B"),
                new KeyValue<>(new Windowed<>("B", new TimeWindow(secondBatchWindow, Long.MAX_VALUE)), "B"),
                new KeyValue<>(new Windowed<>("B", new TimeWindow(secondBatchWindow, Long.MAX_VALUE)), "B:B"),
                new KeyValue<>(new Windowed<>("C", new TimeWindow(firstBatchWindow, Long.MAX_VALUE)), "C"),
                new KeyValue<>(new Windowed<>("C", new TimeWindow(secondBatchWindow, Long.MAX_VALUE)), "C"),
                new KeyValue<>(new Windowed<>("C", new TimeWindow(secondBatchWindow, Long.MAX_VALUE)), "C:C"),
                new KeyValue<>(new Windowed<>("D", new TimeWindow(firstBatchWindow, Long.MAX_VALUE)), "D"),
                new KeyValue<>(new Windowed<>("D", new TimeWindow(secondBatchWindow, Long.MAX_VALUE)), "D"),
                new KeyValue<>(new Windowed<>("D", new TimeWindow(secondBatchWindow, Long.MAX_VALUE)), "D:D"),
                new KeyValue<>(new Windowed<>("E", new TimeWindow(firstBatchWindow, Long.MAX_VALUE)), "E"),
                new KeyValue<>(new Windowed<>("E", new TimeWindow(secondBatchWindow, Long.MAX_VALUE)), "E"),
                new KeyValue<>(new Windowed<>("E", new TimeWindow(secondBatchWindow, Long.MAX_VALUE)), "E:E")
        );
        assertThat(windowedOutput, is(expectResult));

        final Set<String> expectResultString = new HashSet<>(expectResult.size());
        for (final KeyValue<Windowed<String>, String> eachRecord: expectResult) {
            expectResultString.add(eachRecord.toString());
        }

        // check every message is contained in the expect result
        final String[] allRecords = resultFromConsoleConsumer.split("\n");
        for (String record: allRecords) {
            record = "KeyValue(" + record + ")";
            assertTrue(expectResultString.contains(record));
        }
    }

    @Test
    public void shouldAggregate() throws Exception {
        produceMessages(mockTime.milliseconds());
        groupedStream.aggregate(
                initializer,
                aggregator,
                Materialized.<String, Integer, KeyValueStore<Bytes, byte[]>>as("aggregate-by-selected-key"))
                .toStream()
                .to(outputTopic, Produced.with(Serdes.String(), Serdes.Integer()));

        startStreams();

        produceMessages(mockTime.milliseconds());

        final List<KeyValue<String, Integer>> results = receiveMessages(
                new StringDeserializer(),
                new IntegerDeserializer(),
                10);

        results.sort(KStreamAggregationTest::compare);

        assertThat(results, is(Arrays.asList(
                KeyValue.pair("A", 1),
                KeyValue.pair("A", 2),
                KeyValue.pair("B", 1),
                KeyValue.pair("B", 2),
                KeyValue.pair("C", 1),
                KeyValue.pair("C", 2),
                KeyValue.pair("D", 1),
                KeyValue.pair("D", 2),
                KeyValue.pair("E", 1),
                KeyValue.pair("E", 2)
        )));
    }

    public void shouldAggregateWindowed() throws Exception {
        final long firstTimestamp = mockTime.milliseconds();
        mockTime.sleep(1000);
        produceMessages(firstTimestamp);
        final long secondTimestamp = mockTime.milliseconds();
        produceMessages(secondTimestamp);
        produceMessages(secondTimestamp);

        final Serde<Windowed<String>> windowedSerde = WindowedSerdes.timeWindowedSerdeFrom(String.class);
        groupedStream.windowedBy(TimeWindows.of(500L))
                .aggregate(
                        initializer,
                        aggregator,
                        Materialized.with(null, Serdes.Integer())
                )
                .toStream()
                .to(outputTopic, Produced.with(windowedSerde, Serdes.Integer()));

        startStreams();

        final List<KeyValue<Windowed<String>, KeyValue<Integer, Long>>> windowedMessages = receiveMessagesWithTimestamp(
                new TimeWindowedDeserializer<>(),
                new IntegerDeserializer(),
                String.class,
                15);

        // read from ConsoleConsumer
        final String resultFromConsoleConsumer = readWindowedKeyedMessagesViaConsoleConsumer(
                new TimeWindowedDeserializer<String>(),
                new IntegerDeserializer(),
                String.class,
                15,
                true);

        final Comparator<KeyValue<Windowed<String>, KeyValue<Integer, Long>>> comparator =
                Comparator.comparing((KeyValue<Windowed<String>, KeyValue<Integer, Long>> o) -> o.key.key())
                        .thenComparingInt(o -> o.value.key);

        windowedMessages.sort(comparator);

        final long firstWindow = firstTimestamp / 500 * 500;
        final long secondWindow = secondTimestamp / 500 * 500;

        final List<KeyValue<Windowed<String>, KeyValue<Integer, Long>>> expectResult = Arrays.asList(
                new KeyValue<>(new Windowed<>(
                        "A", new TimeWindow(firstWindow, Long.MAX_VALUE)), KeyValue.pair(1, firstTimestamp)),
                new KeyValue<>(new Windowed<>(
                        "A", new TimeWindow(secondWindow, Long.MAX_VALUE)), KeyValue.pair(1, secondTimestamp)),
                new KeyValue<>(new Windowed<>(
                        "A", new TimeWindow(secondWindow, Long.MAX_VALUE)), KeyValue.pair(2, secondTimestamp)),
                new KeyValue<>(new Windowed<>(
                        "B", new TimeWindow(firstWindow, Long.MAX_VALUE)), KeyValue.pair(1, firstTimestamp)),
                new KeyValue<>(new Windowed<>(
                        "B", new TimeWindow(secondWindow, Long.MAX_VALUE)), KeyValue.pair(1, secondTimestamp)),
                new KeyValue<>(new Windowed<>(
                        "B", new TimeWindow(secondWindow, Long.MAX_VALUE)), KeyValue.pair(2, secondTimestamp)),
                new KeyValue<>(new Windowed<>(
                        "C", new TimeWindow(firstWindow, Long.MAX_VALUE)), KeyValue.pair(1, firstTimestamp)),
                new KeyValue<>(new Windowed<>(
                        "C", new TimeWindow(secondWindow, Long.MAX_VALUE)), KeyValue.pair(1, secondTimestamp)),
                new KeyValue<>(new Windowed<>(
                        "C", new TimeWindow(secondWindow, Long.MAX_VALUE)), KeyValue.pair(2, secondTimestamp)),
                new KeyValue<>(new Windowed<>(
                        "D", new TimeWindow(firstWindow, Long.MAX_VALUE)), KeyValue.pair(1, firstTimestamp)),
                new KeyValue<>(new Windowed<>(
                        "D", new TimeWindow(secondWindow, Long.MAX_VALUE)), KeyValue.pair(1, secondTimestamp)),
                new KeyValue<>(new Windowed<>(
                        "D", new TimeWindow(secondWindow, Long.MAX_VALUE)), KeyValue.pair(2, secondTimestamp)),
                new KeyValue<>(new Windowed<>(
                        "E", new TimeWindow(firstWindow, Long.MAX_VALUE)), KeyValue.pair(1, firstTimestamp)),
                new KeyValue<>(new Windowed<>(
                        "E", new TimeWindow(secondWindow, Long.MAX_VALUE)), KeyValue.pair(1, secondTimestamp)),
                new KeyValue<>(new Windowed<>(
                        "E", new TimeWindow(secondWindow, Long.MAX_VALUE)), KeyValue.pair(2, secondTimestamp)));

        assertThat(windowedMessages, is(expectResult));

        final Set<String> expectResultString = new HashSet<>(expectResult.size());
        for (final KeyValue<Windowed<String>, KeyValue<Integer, Long>> eachRecord: expectResult) {
            expectResultString.add("CreateTime:" + eachRecord.value.value
                    + ", " + eachRecord.key.toString()
                    + ", " + eachRecord.value.key);
        }

        // check every message is contained in the expect result
        final String[] allRecords = resultFromConsoleConsumer.split("\n");
        for (final String record: allRecords) {
            assertTrue(expectResultString.contains(record));
        }

    }

    private void shouldCountHelper() throws Exception {
        startStreams();

        produceMessages(mockTime.milliseconds());

        final List<KeyValue<String, Long>> results = receiveMessages(
                new StringDeserializer(),
                new LongDeserializer(),
                10);
        results.sort(KStreamAggregationTest::compare);

        assertThat(results, is(Arrays.asList(
                KeyValue.pair("A", 1L),
                KeyValue.pair("A", 2L),
                KeyValue.pair("B", 1L),
                KeyValue.pair("B", 2L),
                KeyValue.pair("C", 1L),
                KeyValue.pair("C", 2L),
                KeyValue.pair("D", 1L),
                KeyValue.pair("D", 2L),
                KeyValue.pair("E", 1L),
                KeyValue.pair("E", 2L)
        )));
    }

    @Test
    public void shouldCount() throws Exception {
        produceMessages(mockTime.milliseconds());

        groupedStream.count(Materialized.as("count-by-key"))
                .toStream()
                .to(outputTopic, Produced.with(Serdes.String(), Serdes.Long()));

        shouldCountHelper();
    }

    @Test
    public void shouldCountWithInternalStore() throws Exception {
        produceMessages(mockTime.milliseconds());

        groupedStream.count()
                .toStream()
                .to(outputTopic, Produced.with(Serdes.String(), Serdes.Long()));

        shouldCountHelper();
    }

    @Test
    public void shouldGroupByKey() throws Exception {
        final long timestamp = mockTime.milliseconds();
        produceMessages(timestamp);
        produceMessages(timestamp);

        stream.groupByKey(Serialized.with(Serdes.Integer(), Serdes.String()))
                .windowedBy(TimeWindows.of(500L))
                .count()
                .toStream((windowedKey, value) -> windowedKey.key() + "@"
                        + windowedKey.window().start()).to(outputTopic, Produced.with(Serdes.String(), Serdes.Long()));

        startStreams();

        final List<KeyValue<String, Long>> results = receiveMessages(
                new StringDeserializer(),
                new LongDeserializer(),
                10);
        results.sort(KStreamAggregationTest::compare);

        final long window = timestamp / 500 * 500;
        assertThat(results, is(Arrays.asList(
                KeyValue.pair("1@" + window, 1L),
                KeyValue.pair("1@" + window, 2L),
                KeyValue.pair("2@" + window, 1L),
                KeyValue.pair("2@" + window, 2L),
                KeyValue.pair("3@" + window, 1L),
                KeyValue.pair("3@" + window, 2L),
                KeyValue.pair("4@" + window, 1L),
                KeyValue.pair("4@" + window, 2L),
                KeyValue.pair("5@" + window, 1L),
                KeyValue.pair("5@" + window, 2L)
        )));

    }

    public void shouldCountSessionWindows() throws Exception {
        final long sessionGap = 5 * 60 * 1000L;
        final long maintainMillis = sessionGap * 3;

        final long t1 = mockTime.milliseconds() - TimeUnit.MILLISECONDS.convert(1, TimeUnit.HOURS);
        final List<KeyValue<String, String>> t1Messages = Arrays.asList(new KeyValue<>("bob", "start"),
                new KeyValue<>("penny", "start"),
                new KeyValue<>("jo", "pause"),
                new KeyValue<>("emily", "pause"));

        TestUtils.produceKeyValuesSynchronouslyWithTimestamp(
                userSessionsStream,
                t1Messages,
                TestUtils.producerConfig(
                        bootstrapServers,
                        StringSerializer.class,
                        StringSerializer.class,
                        new Properties()),
                t1);

        final long t2 = t1 + (sessionGap / 2);
        TestUtils.produceKeyValuesSynchronouslyWithTimestamp(
                userSessionsStream,
                Collections.singletonList(
                        new KeyValue<>("emily", "resume")
                ),
                TestUtils.producerConfig(
                        bootstrapServers,
                        StringSerializer.class,
                        StringSerializer.class,
                        new Properties()),
                t2);
        final long t3 = t1 + sessionGap + 1;
        TestUtils.produceKeyValuesSynchronouslyWithTimestamp(
                userSessionsStream,
                Arrays.asList(
                        new KeyValue<>("bob", "pause"),
                        new KeyValue<>("penny", "stop")
                ),
                TestUtils.producerConfig(
                        bootstrapServers,
                        StringSerializer.class,
                        StringSerializer.class,
                        new Properties()),
                t3);

        final long t4 = t3 + (sessionGap / 2);
        TestUtils.produceKeyValuesSynchronouslyWithTimestamp(
                userSessionsStream,
                Arrays.asList(
                        new KeyValue<>("bob", "resume"), // bobs session continues
                        new KeyValue<>("jo", "resume")   // jo's starts new session
                ),
                TestUtils.producerConfig(
                        bootstrapServers,
                        StringSerializer.class,
                        StringSerializer.class,
                        new Properties()),
                t4);

        final Map<Windowed<String>, KeyValue<Long, Long>> results = new HashMap<>();
        final CountDownLatch latch = new CountDownLatch(11);

        builder.stream(userSessionsStream, Consumed.with(Serdes.String(), Serdes.String()))
                .groupByKey(Serialized.with(Serdes.String(), Serdes.String()))
                .windowedBy(SessionWindows.with(sessionGap).until(maintainMillis))
                .count()
                .toStream()
                .transform(() -> new Transformer<Windowed<String>, Long, KeyValue<Object, Object>>() {
                    private ProcessorContext context;

                    @Override
                    public void init(final ProcessorContext context) {
                        this.context = context;
                    }

                    @Override
                    public KeyValue<Object, Object> transform(final Windowed<String> key, final Long value) {
                        results.put(key, KeyValue.pair(value, context.timestamp()));
                        latch.countDown();
                        return null;
                    }

                    @Override
                    public void close() {}
                });

        startStreams();
        latch.await(30, TimeUnit.SECONDS);
        assertThat(results.get(new Windowed<>("bob", new SessionWindow(t1, t1))), equalTo(KeyValue.pair(1L, t1)));
        assertThat(results.get(new Windowed<>("penny", new SessionWindow(t1, t1))), equalTo(KeyValue.pair(1L, t1)));
        assertThat(results.get(new Windowed<>("jo", new SessionWindow(t1, t1))), equalTo(KeyValue.pair(1L, t1)));
        assertThat(results.get(new Windowed<>("jo", new SessionWindow(t4, t4))), equalTo(KeyValue.pair(1L, t4)));
        assertThat(results.get(new Windowed<>("emily", new SessionWindow(t1, t2))), equalTo(KeyValue.pair(2L, t2)));
        assertThat(results.get(new Windowed<>("bob", new SessionWindow(t3, t4))), equalTo(KeyValue.pair(2L, t4)));
        assertThat(results.get(new Windowed<>("penny", new SessionWindow(t3, t3))), equalTo(KeyValue.pair(1L, t3)));
    }

    @Test
    public void shouldReduceSessionWindows() throws Exception {
        final long sessionGap = 1000L; // something to do with time
        final long maintainMillis = sessionGap * 3;

        final long t1 = mockTime.milliseconds();
        final List<KeyValue<String, String>> t1Messages = Arrays.asList(new KeyValue<>("bob", "start"),
                new KeyValue<>("penny", "start"),
                new KeyValue<>("jo", "pause"),
                new KeyValue<>("emily", "pause"));

        TestUtils.produceKeyValuesSynchronouslyWithTimestamp(
                userSessionsStream,
                t1Messages,
                TestUtils.producerConfig(
                        bootstrapServers,
                        StringSerializer.class,
                        StringSerializer.class,
                        new Properties()),
                t1);

        final long t2 = t1 + (sessionGap / 2);
        TestUtils.produceKeyValuesSynchronouslyWithTimestamp(
                userSessionsStream,
                Collections.singletonList(
                        new KeyValue<>("emily", "resume")
                ),
                TestUtils.producerConfig(
                        bootstrapServers,
                        StringSerializer.class,
                        StringSerializer.class,
                        new Properties()),
                t2);
        final long t3 = t1 + sessionGap + 1;
        TestUtils.produceKeyValuesSynchronouslyWithTimestamp(
                userSessionsStream,
                Arrays.asList(
                        new KeyValue<>("bob", "pause"),
                        new KeyValue<>("penny", "stop")
                ),
                TestUtils.producerConfig(
                        bootstrapServers,
                        StringSerializer.class,
                        StringSerializer.class,
                        new Properties()),
                t3);

        final long t4 = t3 + (sessionGap / 2);
        TestUtils.produceKeyValuesSynchronouslyWithTimestamp(
                userSessionsStream,
                Arrays.asList(
                        new KeyValue<>("bob", "resume"), // bobs session continues
                        new KeyValue<>("jo", "resume")   // jo's starts new session
                ),
                TestUtils.producerConfig(
                        bootstrapServers,
                        StringSerializer.class,
                        StringSerializer.class,
                        new Properties()),
                t4);

        final Map<Windowed<String>, String> results = new HashMap<>();
        final CountDownLatch latch = new CountDownLatch(11);
        final String userSessionsStore = "UserSessionsStore";
        builder.stream(userSessionsStream, Consumed.with(Serdes.String(), Serdes.String()))
                .groupByKey(Serialized.with(Serdes.String(), Serdes.String()))
                .windowedBy(SessionWindows.with(sessionGap).until(maintainMillis))
                .reduce((value1, value2) -> value1 + ":" + value2, Materialized.as(userSessionsStore))
                .toStream()
                .foreach((key, value) -> {
                    results.put(key, value);
                    latch.countDown();
                });

        startStreams();
        latch.await(30, TimeUnit.SECONDS);
        final ReadOnlySessionStore<String, String> sessionStore =
                kafkaStreams.store(userSessionsStore, QueryableStoreTypes.sessionStore());

        // verify correct data received
        assertThat(results.get(new Windowed<>("bob", new SessionWindow(t1, t1))), equalTo("start"));
        assertThat(results.get(new Windowed<>("penny", new SessionWindow(t1, t1))), equalTo("start"));
        assertThat(results.get(new Windowed<>("jo", new SessionWindow(t1, t1))), equalTo("pause"));
        assertThat(results.get(new Windowed<>("jo", new SessionWindow(t4, t4))), equalTo("resume"));
        assertThat(results.get(new Windowed<>("emily", new SessionWindow(t1, t2))), equalTo("pause:resume"));
        assertThat(results.get(new Windowed<>("bob", new SessionWindow(t3, t4))), equalTo("pause:resume"));
        assertThat(results.get(new Windowed<>("penny", new SessionWindow(t3, t3))), equalTo("stop"));

        // verify can query data via IQ
        final KeyValueIterator<Windowed<String>, String> bob = sessionStore.fetch("bob");
        assertThat(bob.next(),
                equalTo(KeyValue.pair(new Windowed<>("bob", new SessionWindow(t1, t1)), "start")));
        assertThat(bob.next(),
                equalTo(KeyValue.pair(new Windowed<>("bob", new SessionWindow(t3, t4)), "pause:resume")));
        assertFalse(bob.hasNext());

    }

    private static <K extends Comparable, V extends Comparable> int compare(final KeyValue<K, V> o1,
                                                                            final KeyValue<K, V> o2) {
        final int keyComparison = o1.key.compareTo(o2.key);
        if (keyComparison == 0) {
            return o1.value.compareTo(o2.value);
        }
        return keyComparison;
    }

    private void produceMessages(final long timestamp) throws Exception {
        TestUtils.produceKeyValuesSynchronouslyWithTimestamp(
                streamOneInput,
                Arrays.asList(
                        new KeyValue<>(1, "A"),
                        new KeyValue<>(2, "B"),
                        new KeyValue<>(3, "C"),
                        new KeyValue<>(4, "D"),
                        new KeyValue<>(5, "E")),
                TestUtils.producerConfig(
                        bootstrapServers,
                        IntegerSerializer.class,
                        StringSerializer.class,
                        new Properties()),
                timestamp);
    }

    private <K, V> List<KeyValue<K, V>> receiveMessages(final Deserializer<K> keyDeserializer,
                                                        final Deserializer<V> valueDeserializer,
                                                        final int numMessages)
            throws InterruptedException {
        return receiveMessages(keyDeserializer, valueDeserializer, null, numMessages);
    }

    private <K, V> List<KeyValue<K, V>> receiveMessages(final Deserializer<K> keyDeserializer,
                                                        final Deserializer<V> valueDeserializer,
                                                        final Class innerClass,
                                                        final int numMessages) throws InterruptedException {
        final Properties consumerProperties = new Properties();
        consumerProperties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProperties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "kgroupedstream-test-" + getTestNo());
        consumerProperties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                keyDeserializer.getClass().getName());
        consumerProperties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                valueDeserializer.getClass().getName());
        // https://issues.apache.org/jira/browse/KAFKA-10366
        consumerProperties.setProperty(StreamsConfig.WINDOW_SIZE_MS_CONFIG, Long.MAX_VALUE + "");
        if (keyDeserializer instanceof TimeWindowedDeserializer
                || keyDeserializer instanceof SessionWindowedDeserializer) {
            consumerProperties.setProperty(StreamsConfig.DEFAULT_WINDOWED_KEY_SERDE_INNER_CLASS,
                    Serdes.serdeFrom(innerClass).getClass().getName());
        }
        return TestUtils.waitUntilMinKeyValueRecordsReceived(
                consumerProperties,
                outputTopic,
                numMessages,
                60 * 1000);
    }

    private <K, V> List<KeyValue<K, KeyValue<V, Long>>> receiveMessagesWithTimestamp(
            final Deserializer<K> keyDeserializer,
            final Deserializer<V> valueDeserializer,
            final Class innerClass,
            final int numMessages) throws InterruptedException {
        final Properties consumerProperties = new Properties();
        consumerProperties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProperties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "kgroupedstream-test-" + getTestNo());
        consumerProperties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                keyDeserializer.getClass().getName());
        consumerProperties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                valueDeserializer.getClass().getName());
        if (keyDeserializer instanceof TimeWindowedDeserializer
                || keyDeserializer instanceof SessionWindowedDeserializer) {
            consumerProperties.setProperty(StreamsConfig.DEFAULT_WINDOWED_KEY_SERDE_INNER_CLASS,
                    Serdes.serdeFrom(innerClass).getClass().getName());
        }
        return TestUtils.waitUntilMinKeyValueWithTimestampRecordsReceived(
                consumerProperties,
                outputTopic,
                numMessages,
                60 * 1000);
    }

    private <K, V> String readWindowedKeyedMessagesViaConsoleConsumer(final Deserializer<K> keyDeserializer,
                                                                      final Deserializer<V> valueDeserializer,
                                                                      final Class<?> innerClass,
                                                                      final int numMessages,
                                                                      final boolean printTimestamp) {
        final Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "group-" + groupIdIndex.getAndIncrement());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        final Map<String, String> configs = new HashMap<>();
        Serde<?> serde = Serdes.serdeFrom(innerClass);
        configs.put(StreamsConfig.DEFAULT_WINDOWED_KEY_SERDE_INNER_CLASS, serde.getClass().getName());
        serde.close();
        // https://issues.apache.org/jira/browse/KAFKA-10366
        configs.put(StreamsConfig.WINDOW_SIZE_MS_CONFIG, Long.toString(Long.MAX_VALUE));
        keyDeserializer.configure(configs, true);

        final KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singleton(outputTopic));
        final StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < numMessages; ) {
            final ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofSeconds(1));
            for (ConsumerRecord<byte[], byte[]> record : records) {
                if (printTimestamp) {
                    stringBuilder.append(record.timestampType());
                    stringBuilder.append(":");
                    stringBuilder.append(record.timestamp());
                    stringBuilder.append(", ");
                }
                stringBuilder.append(keyDeserializer.deserialize(outputTopic, record.key()).toString());
                stringBuilder.append(", ");
                stringBuilder.append(valueDeserializer.deserialize(outputTopic, record.value()).toString());
                stringBuilder.append("\n");
                i++;
            }
        }
        consumer.close();
        return stringBuilder.toString();
    }
}
