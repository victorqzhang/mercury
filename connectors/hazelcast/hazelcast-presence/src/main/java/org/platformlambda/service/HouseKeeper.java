/*

    Copyright 2018-2019 Accenture Technology

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

package org.platformlambda.service;

import org.platformlambda.MainApp;
import org.platformlambda.core.exception.AppException;
import org.platformlambda.core.models.EventEnvelope;
import org.platformlambda.core.models.Kv;
import org.platformlambda.core.models.LambdaFunction;
import org.platformlambda.core.system.Platform;
import org.platformlambda.core.system.PostOffice;
import org.platformlambda.core.util.Utility;
import org.platformlambda.hazelcast.TopicManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;

public class HouseKeeper implements LambdaFunction {
    private static final Logger log = LoggerFactory.getLogger(HouseKeeper.class);

    private static final String MANAGER = MainApp.MANAGER;
    private static final String TYPE = "type";
    private static final String ORIGIN = "origin";
    private static final String ALIVE = "alive";
    private static final String LEAVE = "leave";
    private static final String TOKEN = "token";
    private static final String DOWNLOAD = "download";
    private static final String TIMESTAMP = "timestamp";
    private static final long ONE_MINUTE = 60 * 1000;
    // Topic expiry is 60 seconds, deletion is 2 minutes
    private static final long EXPIRY = 2 * ONE_MINUTE;

    private static final ConcurrentMap<String, Member> monitors = new ConcurrentHashMap<>();

    public static Map<String, Date> getMonitors() {
        Map<String, Date> result = new HashMap<>();
        for (String m: monitors.keySet()) {
            result.put(m, new Date(monitors.get(m).updated));
        }
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object handleEvent(Map<String, String> headers, Object body, int instance) throws Exception {
        if (ALIVE.equals(headers.get(TYPE)) && headers.containsKey(TIMESTAMP) && headers.containsKey(TOKEN)
                && headers.containsKey(ORIGIN)) {
            String origin = headers.get(ORIGIN);
            String timestamp = headers.get(TIMESTAMP);
            long time = Utility.getInstance().timestamp2ms(timestamp);
            long now = System.currentTimeMillis();
            if (time > now) {
                time = now;
            } else {
                if (now - time > EXPIRY) {
                    return false;
                }
            }
            String me = Platform.getInstance().getOrigin();
            if (!monitors.containsKey(origin)) {
                log.info("Registered monitor {} {}", origin, me.equals(origin)? "(me)" : "(peer)");
            }
            monitors.put(origin, new Member(headers.get(TOKEN), time));
            removeExpiredMonitors();
            if (me.equals(origin)) {
                log.debug("Found {} monitor{}", monitors.size(), monitors.size() == 1 ? "" : "s");
                /*
                 * Skip signals from other presence monitor.
                 * Check only when it is my turn.
                 */
                String leader = getLeader(me);
                boolean myTurn = leader.equals(me);
                List<String> expired = findExpiredTopics();
                PostOffice po = PostOffice.getInstance();
                for (String e : expired) {
                    // delete the expired topic
                    if (myTurn) {
                        log.info("Removing expired topic {}", e);
                        po.send(MANAGER, new Kv(TYPE, LEAVE), new Kv(ORIGIN, e));
                    } else {
                        log.info("Detected expired topic {}", e);
                    }
                }
            } else if (body instanceof List) {
                // compare connection list
                Map<String, Object> connections = MonitorService.getConnections();
                List<String> myConnections = new ArrayList<>(connections.keySet());
                List<String> peerConnections = (List<String>) body;
                if (!sameList(myConnections, peerConnections)) {
                    log.warn("Sync up connection list with peers");
                    // download current connections from peers
                    EventEnvelope event = new EventEnvelope();
                    event.setTo(MainApp.PRESENCE_HANDLER);
                    event.setHeader(TYPE, DOWNLOAD);
                    event.setHeader(ORIGIN, me);
                    PostOffice.getInstance().send(MainApp.PRESENCE_MONITOR, event.toBytes());
                }
            }
        }
        return null;
    }

    private boolean sameList(List<String> a, List<String> b) {
        if (a.size() > 1) {
            Collections.sort(a);
        }
        if (b.size() > 1) {
            Collections.sort(b);
        }
        return a.toString().equals(b.toString());
    }

    private List<String> findExpiredTopics() {
        Utility util = Utility.getInstance();
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        try {
            Map<String, String> registered = getTopics();
            for (String node: registered.keySet()) {
                String timestamp = registered.get(node);
                long time = util.str2date(timestamp).getTime();
                if (now - time > EXPIRY) {
                    expired.add(node);
                }
            }
        } catch (TimeoutException | IOException | AppException e) {
            log.error("Unable to scan for expired topics - {}", e.getMessage());
        }
        return expired;
    }

    private void removeExpiredMonitors() {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (String k: monitors.keySet()) {
            long time = monitors.get(k).updated;
            if (now - time > EXPIRY) {
                expired.add(k);
            }
        }
        if (!expired.isEmpty()) {
            for (String k: expired) {
                monitors.remove(k);
                log.info("Removed monitor {}", k);
            }
        }
    }

    private String getLeader(String me) {
        /*
         * The member with the highest token value wins.
         * Default is "me" when there are no bidders.
         */
        String leader = me;
        int base = 0;
        for (String k: monitors.keySet()) {
            if (leader == null) {
                leader = k;
            }
            int token = monitors.get(k).token;
            if (token > base) {
                leader = k;
                base = token;
            }
        }
        return leader;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> getTopics() throws TimeoutException, IOException, AppException {
        PostOffice po = PostOffice.getInstance();
        EventEnvelope res = po.request(MANAGER, 30000, new Kv(TYPE, TopicManager.LIST_TIMESTAMP));
        return res.getBody() instanceof Map? (Map<String, String>) res.getBody() : new HashMap<>();
    }

    private class Member {

        public int token;
        public long updated;

        public Member(String token, long updated) {
            this.token = Utility.getInstance().str2int(token);
            this.updated = updated;
        }

    }

}
