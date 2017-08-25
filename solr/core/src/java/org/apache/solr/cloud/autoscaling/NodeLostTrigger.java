/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud.autoscaling;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.solr.client.solrj.cloud.autoscaling.TriggerEventType;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.util.TimeSource;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trigger for the {@link TriggerEventType#NODELOST} event
 */
public class NodeLostTrigger extends TriggerBase {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final CoreContainer container;
  private final TimeSource timeSource;

  private boolean isClosed = false;

  private Set<String> lastLiveNodes;

  private Map<String, Long> nodeNameVsTimeRemoved = new HashMap<>();

  public NodeLostTrigger(String name, Map<String, Object> properties,
                         CoreContainer container) {
    super(name, properties, container.getResourceLoader(), container.getZkController().getZkClient());
    this.container = container;
    this.timeSource = TimeSource.CURRENT_TIME;
    lastLiveNodes = new HashSet<>(container.getZkController().getZkStateReader().getClusterState().getLiveNodes());
    log.debug("Initial livenodes: {}", lastLiveNodes);
  }

  @Override
  public void init() {
    List<Map<String, String>> o = (List<Map<String, String>>) properties.get("actions");
    if (o != null && !o.isEmpty()) {
      for (int i = 0; i < o.size(); i++) {
        Map<String, String> map = o.get(i);
        actions.get(i).init(map);
      }
    }
    // pick up lost nodes for which marker paths were created
    try {
      List<String> lost = container.getZkController().getZkClient().getChildren(ZkStateReader.SOLR_AUTOSCALING_NODE_LOST_PATH, null, true);
      lost.forEach(n -> {
        // don't add nodes that have since came back
        if (!lastLiveNodes.contains(n)) {
          log.debug("Adding lost node from marker path: {}", n);
          nodeNameVsTimeRemoved.put(n, timeSource.getTime());
        }
        removeMarker(n);
      });
    } catch (KeeperException.NoNodeException e) {
      // ignore
    } catch (KeeperException | InterruptedException e) {
      log.warn("Exception retrieving nodeLost markers", e);
    }
  }

  @Override
  public void restoreState(AutoScaling.Trigger old) {
    assert old.isClosed();
    if (old instanceof NodeLostTrigger) {
      NodeLostTrigger that = (NodeLostTrigger) old;
      assert this.name.equals(that.name);
      this.lastLiveNodes = new HashSet<>(that.lastLiveNodes);
      this.nodeNameVsTimeRemoved = new HashMap<>(that.nodeNameVsTimeRemoved);
    } else  {
      throw new SolrException(SolrException.ErrorCode.INVALID_STATE,
          "Unable to restore state from an unknown type of trigger");
    }
  }

  @Override
  protected Map<String, Object> getState() {
    Map<String,Object> state = new HashMap<>();
    state.put("lastLiveNodes", lastLiveNodes);
    state.put("nodeNameVsTimeRemoved", nodeNameVsTimeRemoved);
    return state;
  }

  @Override
  protected void setState(Map<String, Object> state) {
    this.lastLiveNodes.clear();
    this.nodeNameVsTimeRemoved.clear();
    Collection<String> lastLiveNodes = (Collection<String>)state.get("lastLiveNodes");
    if (lastLiveNodes != null) {
      this.lastLiveNodes.addAll(lastLiveNodes);
    }
    Map<String,Long> nodeNameVsTimeRemoved = (Map<String,Long>)state.get("nodeNameVsTimeRemoved");
    if (nodeNameVsTimeRemoved != null) {
      this.nodeNameVsTimeRemoved.putAll(nodeNameVsTimeRemoved);
    }
  }

  @Override
  public void run() {
    try {
      synchronized (this) {
        if (isClosed) {
          log.warn("NodeLostTrigger ran but was already closed");
          throw new RuntimeException("Trigger has been closed");
        }
      }

      ZkStateReader reader = container.getZkController().getZkStateReader();
      Set<String> newLiveNodes = reader.getClusterState().getLiveNodes();
      log.debug("Running NodeLostTrigger: {} with currently live nodes: {}", name, newLiveNodes);

      // have any nodes that we were tracking been added to the cluster?
      // if so, remove them from the tracking map
      Set<String> trackingKeySet = nodeNameVsTimeRemoved.keySet();
      trackingKeySet.removeAll(newLiveNodes);

      // have any nodes been removed?
      Set<String> copyOfLastLiveNodes = new HashSet<>(lastLiveNodes);
      copyOfLastLiveNodes.removeAll(newLiveNodes);
      copyOfLastLiveNodes.forEach(n -> {
        log.debug("Tracking lost node: {}", n);
        nodeNameVsTimeRemoved.put(n, timeSource.getTime());
      });

      // has enough time expired to trigger events for a node?
      List<String> nodeNames = new ArrayList<>();
      List<Long> times = new ArrayList<>();
      for (Iterator<Map.Entry<String, Long>> it = nodeNameVsTimeRemoved.entrySet().iterator(); it.hasNext(); ) {
        Map.Entry<String, Long> entry = it.next();
        String nodeName = entry.getKey();
        Long timeRemoved = entry.getValue();
        long now = timeSource.getTime();
        if (TimeUnit.SECONDS.convert(now - timeRemoved, TimeUnit.NANOSECONDS) >= getWaitForSecond()) {
          nodeNames.add(nodeName);
          times.add(timeRemoved);
        }
      }
      // fire!
      AutoScaling.TriggerEventProcessor processor = processorRef.get();
      if (!nodeNames.isEmpty()) {
        if (processor != null) {
          log.debug("NodeLostTrigger firing registered processor for lost nodes: {}", nodeNames);
          if (processor.process(new NodeLostEvent(getEventType(), getName(), times, nodeNames)))  {
            // remove from tracking set only if the fire was accepted
            nodeNames.forEach(n -> {
              nodeNameVsTimeRemoved.remove(n);
              removeMarker(n);
            });
          } else  {
            log.debug("NodeLostTrigger listener for lost nodes: {} is not ready, will try later", nodeNames);
          }
        } else  {
          nodeNames.forEach(n -> {
            nodeNameVsTimeRemoved.remove(n);
            removeMarker(n);
          });
        }
      }
      lastLiveNodes = new HashSet<>(newLiveNodes);
    } catch (RuntimeException e) {
      log.error("Unexpected exception in NodeLostTrigger", e);
    }
  }

  private void removeMarker(String nodeName) {
    String path = ZkStateReader.SOLR_AUTOSCALING_NODE_LOST_PATH + "/" + nodeName;
    try {
      if (container.getZkController().getZkClient().exists(path, true)) {
        container.getZkController().getZkClient().delete(path, -1, true);
      }
    } catch (KeeperException.NoNodeException e) {
      // ignore
    } catch (KeeperException | InterruptedException e) {
      log.warn("Exception removing nodeLost marker " + nodeName, e);
    }
  }

  public static class NodeLostEvent extends TriggerEvent {

    public NodeLostEvent(TriggerEventType eventType, String source, List<Long> times, List<String> nodeNames) {
      // use the oldest time as the time of the event
      super(eventType, source, times.get(0), null);
      properties.put(NODE_NAMES, nodeNames);
      properties.put(EVENT_TIMES, times);
    }
  }
}
