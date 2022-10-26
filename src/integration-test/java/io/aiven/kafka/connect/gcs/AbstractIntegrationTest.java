/*
 * Copyright 2020 Aiven Oy
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
 */

package io.aiven.kafka.connect.gcs;

import static org.awaitility.Awaitility.await;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import io.aiven.kafka.connect.common.config.CompressionType;
import io.aiven.kafka.connect.gcs.testutils.BucketAccessor;

import com.github.dockerjava.api.model.Ulimit;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;

class AbstractIntegrationTest {
    protected static final String TEST_TOPIC_0 = "test-topic-0";
    protected static final String TEST_TOPIC_1 = "test-topic-1";

    protected static final int OFFSET_FLUSH_INTERVAL_MS = 5000;

    protected static String gcsCredentialsPath; // NOPMD mutable static state
    protected static String gcsCredentialsJson; // NOPMD mutable static state

    protected static String testBucketName; // NOPMD mutable static state

    protected static String gcsPrefix; // NOPMD mutable static state

    protected static BucketAccessor testBucketAccessor; // NOPMD mutable static state

    protected static File pluginDir; // NOPMD mutable static state

    protected AbstractIntegrationTest() {
    }

    @BeforeAll
    static void setUpAll() throws IOException, InterruptedException {
        gcsCredentialsPath = System.getProperty("integration-test.gcs.credentials.path");
        gcsCredentialsJson = System.getProperty("integration-test.gcs.credentials.json");

        testBucketName = System.getProperty("integration-test.gcs.bucket");

        final Storage storage = StorageOptions.newBuilder()
                .setCredentials(GoogleCredentialsBuilder.build(gcsCredentialsPath, gcsCredentialsJson))
                .build()
                .getService();
        testBucketAccessor = new BucketAccessor(storage, testBucketName);
        testBucketAccessor.ensureWorking();

        gcsPrefix = "gcs-connector-for-apache-kafka-test-"
                + ZonedDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "/";

        final File testDir = Files.createTempDirectory("gcs-connector-for-apache-kafka-test-").toFile();

        pluginDir = new File(testDir, "plugins/gcs-connector-for-apache-kafka/");
        assert pluginDir.mkdirs();

        final File distFile = new File(System.getProperty("integration-test.distribution.file.path"));
        assert distFile.exists();

        final String cmd = String.format("tar -xf %s --strip-components=1 -C %s", distFile.toString(),
                pluginDir.toString());
        final Process process = Runtime.getRuntime().exec(cmd);
        assert process.waitFor() == 0;
    }

    protected String getBlobName(final int partition, final int startOffset, final String compression) {
        final String result = String.format("%s%s-%d-%d", gcsPrefix, TEST_TOPIC_0, partition, startOffset);
        return result + CompressionType.forName(compression).extension();
    }

    protected String getBlobName(final String key, final String compression) {
        final String result = String.format("%s%s", gcsPrefix, key);
        return result + CompressionType.forName(compression).extension();
    }

    protected void awaitAllBlobsWritten(final int expectedBlobCount) {
        await("All expected files stored on GCS").atMost(Duration.ofMillis(OFFSET_FLUSH_INTERVAL_MS * 3))
                .pollInterval(Duration.ofMillis(300))
                .until(() -> testBucketAccessor.getBlobNames(gcsPrefix).size() >= expectedBlobCount);

    }

    protected KafkaContainer createKafkaContainer() {
        return new KafkaContainer("5.2.1").withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withNetwork(Network.newNetwork())
                .withExposedPorts(KafkaContainer.KAFKA_PORT, 9092)
                .withCreateContainerCmdModifier(
                        cmd -> cmd.getHostConfig().withUlimits(List.of(new Ulimit("nofile", 30_000L, 30_000L))));
    }
}
