/*

    Copyright 2018-2022 Accenture Technology

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */

package org.platformlambda.cloud.reporter;

import org.platformlambda.cloud.ServiceLifeCycle;
import org.platformlambda.cloud.services.ServiceRegistry;
import org.platformlambda.core.models.*;
import org.platformlambda.core.system.*;
import org.platformlambda.core.util.AppConfigReader;
import org.platformlambda.core.util.Utility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class PresenceConnector implements LambdaFunction {
    private static final Logger log = LoggerFactory.getLogger(PresenceConnector.class);

    private static final String APP_GROUP = ServiceRegistry.APP_GROUP;
    private static final String TYPE = "type";
    private static final String INIT = "init";
    private static final String DONE = "done";
    private static final String INSTANCE = "instance";
    private static final String LOOP_BACK = "loopback";
    private static final String REPLY_TO = "reply_to";
    private static final String ALIVE = "keep-alive";
    private static final String JOIN = "join";
    private static final String LEAVE = "leave";
    private static final String ORIGIN = "origin";
    private static final String INFO = "info";
    private static final String READY = "ready";
    private static final String SEQ = "seq";
    private static final String CREATED = "created";
    private static final String ELAPSED = "elapsed";
    private static final String NAME = "name";
    private static final String VERSION = "version";
    private static final String GROUP = "group";
    private static final String TOPIC = "topic";
    private static final long startTime = System.currentTimeMillis();
    private static final PresenceConnector instance = new PresenceConnector();
    private final String created, monitorTopic;
    public enum State {
        UNASSIGNED, CONNECTED, DISCONNECTED
    }
    private final int closedUserGroup;
    private State state = State.UNASSIGNED;
    private String monitor;
    private long seq = 0;
    private boolean ready = false;
    private boolean active = true;
    private String topicPartition = null;

    private PresenceConnector() {
        Utility util = Utility.getInstance();
        AppConfigReader config = AppConfigReader.getInstance();
        monitorTopic = config.getProperty("monitor.topic", "service.monitor");
        created = Utility.getInstance().date2str(new Date(startTime), true);
        int maxGroups = Math.min(30,
                Math.max(3, util.str2int(config.getProperty("max.closed.user.groups", "30"))));
        closedUserGroup = util.str2int(config.getProperty("closed.user.group", "1"));
        if (closedUserGroup < 1 || closedUserGroup > maxGroups) {
            log.error("closed.user.group is invalid. Please select a number from 1 to "+maxGroups);
            System.exit(-1);
        }
    }

    public static PresenceConnector getInstance() {
        return instance;
    }

    public void setActive(String origin, String user, boolean active) {
        if (active && !this.active) {
            this.active = true;
            log.warn("Resume {} by {}", origin, user);
        }
        if (!active && this.active) {
            this.active = false;
            log.warn("Suspend {} by {}", origin, user);
        }
    }

    public String getTopic() {
        return topicPartition;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object handleEvent(Map<String, String> headers, Object body, int instance) throws IOException {
        Platform platform  = Platform.getInstance();
        PostOffice po = PostOffice.getInstance();
        String route;

        if (headers.containsKey(WsEnvelope.TYPE)) {
            switch (headers.get(WsEnvelope.TYPE)) {
                case WsEnvelope.OPEN:
                    // in case the previous connection was not closed properly
                    if (topicPartition != null) {
                        closeConsumer();
                    }
                    // the open event contains route, txPath, ip, path, query and token
                    route = headers.get(WsEnvelope.ROUTE);
                    String ip = headers.get(WsEnvelope.IP);
                    String path = headers.get(WsEnvelope.PATH);
                    monitor = headers.get(WsEnvelope.TX_PATH);
                    ready = false;
                    state = State.CONNECTED;
                    sendAppInfo(seq++, false);
                    log.info("Connected {}, {}, {}", route, ip, path);
                    break;
                case WsEnvelope.CLOSE:
                    // the close event contains route and token for this websocket
                    route = headers.get(WsEnvelope.ROUTE);
                    monitor = null;
                    ready = false;
                    state = State.DISCONNECTED;
                    log.info("Disconnected {}", route);
                    if (topicPartition != null) {
                        closeConsumer();
                    }
                    // tell service registry to clear routing table
                    po.send(ServiceDiscovery.SERVICE_REGISTRY, new Kv(TYPE, LEAVE),
                            new Kv(ORIGIN, platform.getOrigin()));
                    break;
                case WsEnvelope.BYTES:
                    route = headers.get(WsEnvelope.ROUTE);
                    EventEnvelope event = new EventEnvelope();
                    event.load((byte[]) body);
                    if (READY.equals(event.getTo()) && event.getHeaders().containsKey(VERSION) &&
                            event.getHeaders().containsKey(TOPIC)) {
                        ready = true;
                        log.info("Activated {}", route);
                        String platformVersion = event.getHeaders().get(VERSION);
                        topicPartition = event.getHeaders().get(TOPIC);
                        startConsumer();
                        // tell peers that this server has joined
                        po.send(ServiceDiscovery.SERVICE_REGISTRY, new Kv(TYPE, JOIN),
                                new Kv(VERSION, platformVersion), new Kv(TOPIC, topicPartition),
                                new Kv(ORIGIN, platform.getOrigin()));
                        // info presence monitor that this app is activated
                        sendAppInfo(seq++, true);
                    } else {
                        po.send(event);
                    }
                    break;
                case WsEnvelope.MAP:
                    if (body instanceof Map) {
                        Map<String, Object> data = (Map<String, Object>) body;
                        if (ALIVE.equals(data.get(TYPE))) {
                            sendAppInfo(seq++, true);
                        }
                    }
                    break;
                case WsEnvelope.STRING:
                    log.debug("{}", body);
                    break;
                default:
                    break;
            }
        }
        return null;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    public boolean isReady() {
        return ready;
    }

    private void sendAppInfo(long n, boolean alive) {
        if (monitor != null) {
            try {
                Utility util = Utility.getInstance();
                Platform platform = Platform.getInstance();
                PostOffice po = PostOffice.getInstance();
                VersionInfo app = util.getVersionInfo();
                EventEnvelope info = new EventEnvelope();
                info.setTo(alive? ALIVE : INFO).setHeader(CREATED, created).setHeader(SEQ, n)
                    .setHeader(NAME, Platform.getInstance().getName())
                    .setHeader(VERSION, app.getVersion())
                    .setHeader(GROUP, closedUserGroup)
                    .setHeader(TYPE, ServerPersonality.getInstance().getType());
                if (alive) {
                    info.setHeader(ELAPSED, util.elapsedTime(System.currentTimeMillis() - startTime));
                }
                if (topicPartition != null) {
                    info.setHeader(TOPIC, topicPartition);
                }
                String appId = platform.getAppId();
                if (appId != null) {
                    info.setHeader(INSTANCE, appId);
                }
                po.send(monitor, info.toBytes());
                // send keep-alive to peers in the same user group
                if (alive && active) {
                    po.send(ServiceDiscovery.SERVICE_REGISTRY + APP_GROUP + closedUserGroup,
                            new Kv(TYPE, ALIVE), new Kv(TOPIC, topicPartition),
                            new Kv(ORIGIN, platform.getOrigin()));
                }

            } catch (IOException e) {
                log.error("Unable to send application info to presence monitor - {}", e.getMessage());
            }
        }
    }

    private void startConsumer() throws IOException {
        if (topicPartition != null && topicPartition.contains("-")) {
            AppConfigReader config = AppConfigReader.getInstance();
            Platform platform = Platform.getInstance();
            PostOffice po = PostOffice.getInstance();
            PubSub ps = PubSub.getInstance();
            Utility util = Utility.getInstance();
            int hyphen = topicPartition.lastIndexOf('-');
            String topic = topicPartition.substring(0, hyphen);
            int partition = util.str2int(topicPartition.substring(hyphen + 1));
            String groupId = config.getProperty("default.app.group.id", "appGroup");
            String clientId = platform.getOrigin();
            // subscribe to closed user group
            final AtomicBoolean topicPending = new AtomicBoolean(true);
            LambdaFunction topicControl = (headers, body, instance) -> {
                if (INIT.equals(body) && INIT.equals(headers.get(TYPE))) {
                    if (topicPending.get()) {
                        topicPending.set(false);
                        po.send(ServiceDiscovery.SERVICE_REGISTRY + APP_GROUP + closedUserGroup,
                                new Kv(TYPE, JOIN), new Kv(ORIGIN, platform.getOrigin()), new Kv(TOPIC, topicPartition));
                    }
                    String INIT_HANDLER = INIT + "." + monitorTopic + "." + closedUserGroup;
                    if (platform.hasRoute(INIT_HANDLER)) {
                        po.send(INIT_HANDLER, DONE);
                    }
                }
                return true;
            };
            ps.subscribe(monitorTopic, closedUserGroup, topicControl, clientId+"-1", groupId,
                        String.valueOf(ServiceLifeCycle.INITIALIZE));
            // subscribe to assigned topic and partition
            final AtomicBoolean appPending = new AtomicBoolean(true);
            LambdaFunction appControl = (headers, body, instance) -> {
                if (LOOP_BACK.equals(body) && headers.containsKey(REPLY_TO) && clientId.equals(headers.get(ORIGIN))) {
                    po.send(headers.get(REPLY_TO), true);
                }
                if (INIT.equals(body) && INIT.equals(headers.get(TYPE))) {
                    if (appPending.get()) {
                        appPending.set(false);
                        log.info("Connected to Closed User Group {}", closedUserGroup);
                    }
                    String INIT_HANDLER = INIT + "." + topic + "." + partition;
                    if (platform.hasRoute(INIT_HANDLER)) {
                        po.send(INIT_HANDLER, DONE);
                    }
                }
                return true;
            };
            ps.subscribe(topic, partition, appControl, clientId+"-2", groupId,
                            String.valueOf(ServiceLifeCycle.INITIALIZE));
        }
    }

    private void closeConsumer() throws IOException {
        if (topicPartition != null && topicPartition.contains("-")) {
            PubSub ps = PubSub.getInstance();
            Utility util = Utility.getInstance();
            int hyphen = topicPartition.lastIndexOf('-');
            String topic = topicPartition.substring(0, hyphen);
            int partition = util.str2int(topicPartition.substring(hyphen + 1));
            ps.unsubscribe(topic, partition);
            ps.unsubscribe(monitorTopic, closedUserGroup);
            ServiceLifeCycle.releaseConnecton("disconnected from "+topicPartition);
            topicPartition = null;
        }

    }

}
