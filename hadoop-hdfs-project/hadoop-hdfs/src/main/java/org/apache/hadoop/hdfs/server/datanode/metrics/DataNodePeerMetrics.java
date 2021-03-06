/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdfs.server.datanode.metrics;


import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.metrics2.MetricsJsonBuilder;
import org.apache.hadoop.metrics2.lib.RollingAverages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * This class maintains DataNode peer metrics (e.g. numOps, AvgTime, etc.) for
 * various peer operations.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class DataNodePeerMetrics {

  public static final Logger LOG = LoggerFactory.getLogger(
      DataNodePeerMetrics.class);

  private final RollingAverages sendPacketDownstreamRollingAvgerages;

  private final String name;

  /**
   * Threshold in milliseconds below which a DataNode is definitely not slow.
   */
  private static final long LOW_THRESHOLD_MS = 5;
  private static final long MIN_OUTLIER_DETECTION_NODES = 10;

  private final OutlierDetector slowNodeDetector;

  /**
   * Minimum number of packet send samples which are required to qualify
   * for outlier detection. If the number of samples is below this then
   * outlier detection is skipped.
   */
  @VisibleForTesting
  static final long MIN_OUTLIER_DETECTION_SAMPLES = 1000;

  public DataNodePeerMetrics(
      final String name,
      final long windowSizeMs,
      final int numWindows) {
    this.name = name;
    this.slowNodeDetector = new OutlierDetector(MIN_OUTLIER_DETECTION_NODES,
        LOW_THRESHOLD_MS);
    sendPacketDownstreamRollingAvgerages = new RollingAverages(
        windowSizeMs, numWindows);
  }

  public String name() {
    return name;
  }

  /**
   * Creates an instance of DataNodePeerMetrics, used for registration.
   */
  public static DataNodePeerMetrics create(Configuration conf, String dnName) {
    final String name = "DataNodePeerActivity-" + (dnName.isEmpty()
        ? "UndefinedDataNodeName" + ThreadLocalRandom.current().nextInt()
        : dnName.replace(':', '-'));

    final long windowSizeMs = conf.getTimeDuration(
            DFSConfigKeys.DFS_METRICS_ROLLING_AVERAGES_WINDOW_LENGTH_KEY,
            DFSConfigKeys.DFS_METRICS_ROLLING_AVERAGES_WINDOW_LENGTH_DEFAULT,
            TimeUnit.MILLISECONDS);
    final int numWindows = conf.getInt(
        DFSConfigKeys.DFS_METRICS_ROLLING_AVERAGE_NUM_WINDOWS_KEY,
        DFSConfigKeys.DFS_METRICS_ROLLING_AVERAGE_NUM_WINDOWS_DEFAULT);

    return new DataNodePeerMetrics(
        name,
        windowSizeMs,
        numWindows);
  }

  /**
   * Adds invocation and elapsed time of SendPacketDownstream for peer.
   * <p>
   * The caller should pass in a well-formatted peerAddr. e.g.
   * "[192.168.1.110:1010]" is good. This will be translated into a full
   * qualified metric name, e.g. "[192.168.1.110:1010]AvgTime".
   * </p>
   */
  public void addSendPacketDownstream(
      final String peerAddr,
      final long elapsedMs) {
    sendPacketDownstreamRollingAvgerages.add(peerAddr, elapsedMs);
  }

  /**
   * Dump SendPacketDownstreamRollingAvgTime metrics as JSON.
   */
  public String dumpSendPacketDownstreamAvgInfoAsJson() {
    final MetricsJsonBuilder builder = new MetricsJsonBuilder(null);
    sendPacketDownstreamRollingAvgerages.snapshot(builder, true);
    return builder.toString();
  }

  /**
   * Collects states maintained in {@link ThreadLocal}, if any.
   */
  public void collectThreadLocalStates() {
    sendPacketDownstreamRollingAvgerages.collectThreadLocalStates();
  }

  /**
   * Retrieve the set of dataNodes that look significantly slower
   * than their peers.
   */
  public Map<String, Double> getOutliers() {
    // This maps the metric name to the aggregate latency.
    // The metric name is the datanode ID.
    final Map<String, Double> stats =
        sendPacketDownstreamRollingAvgerages.getStats(
            MIN_OUTLIER_DETECTION_SAMPLES);
    LOG.trace("DataNodePeerMetrics: Got stats: {}", stats);

    return slowNodeDetector.getOutliers(stats);
  }
}
