package org.platformlambda.core.websocket.client;

import org.platformlambda.core.exception.AppException;
import org.platformlambda.core.models.Kv;
import org.platformlambda.core.models.LambdaFunction;
import org.platformlambda.core.models.WsEnvelope;
import org.platformlambda.core.models.WsRouteSet;
import org.platformlambda.core.system.PostOffice;
import org.platformlambda.core.system.WsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.*;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeoutException;

@ClientEndpoint
public class SimpleClientEndpoint {
    private static final Logger log = LoggerFactory.getLogger(SimpleClientEndpoint.class);
    private static final WsRegistry registry = WsRegistry.getInstance();
    private final LambdaFunction service;
    private final URI uri;
    private Session session;
    private String route, txPath;
    private long disconnectTime = 0;
    private boolean open = false;

    public SimpleClientEndpoint(LambdaFunction service, URI uri) {
        this.service = service;
        this.uri = uri;
    }

    public boolean isConnected() {
        return open && session != null && session.isOpen();
    }

    public String getRoute() {
        return route;
    }

    public String getTxPath() {
        return txPath;
    }

    public URI getUri() {
        return uri;
    }

    public boolean justDisconnected() {
        return !isConnected() && System.currentTimeMillis() - disconnectTime < 5000;
    }

    public void close(CloseReason reason) throws IOException {
        if (isConnected()) {
            session.close(reason);
        }
    }

    @OnOpen
    public void onOpen(Session session) {
        this.open = true;
        this.session = session;
        // create websocket routing metadata
        WsRouteSet rs = new WsRouteSet("ws.client");
        this.route = rs.getRoute();
        this.txPath = rs.getTxPath();
        try {
            WsEnvelope envelope = new WsEnvelope(rs.getRoute(), rs.getTxPath(), uri.getHost(), uri.getPath(), uri.getQuery());
            // setup listener and transmitter
            registry.createHandler(service, session, envelope);
            // send open event to the newly created websocket transmitter and wait for completion
            PostOffice.getInstance().request(envelope.txPath, 5000,
                    new Kv(WsEnvelope.ROUTE, route), new Kv(WsEnvelope.TYPE, WsEnvelope.OPEN));
            // then inform the sender function
            PostOffice.getInstance().send(route, new Kv(WsEnvelope.TYPE, WsEnvelope.OPEN),
                    new Kv(WsEnvelope.ROUTE, route), new Kv(WsEnvelope.TX_PATH, envelope.txPath),
                    new Kv(WsEnvelope.IP, envelope.ip), new Kv(WsEnvelope.PATH, envelope.path),
                    new Kv(WsEnvelope.QUERY, envelope.query == null? "" : envelope.query),
                    new Kv(WsEnvelope.TOKEN, envelope.origin));

            log.info("Session-{} {} connected to {} {}, {}", session.getId(),
                    service.getClass().getSimpleName(), route, envelope.ip, uri);

        } catch (IOException | TimeoutException e) {
            log.error("Unable to connect to {}, {}", uri, e.getMessage());
        } catch (AppException e) {
            log.error("Unable to connect to {}, status={}, error={}", uri, e.getStatus(), e.getMessage());
        }
    }

    @OnMessage
    public void onText(String message, Session session) {
        String route = registry.getRoute(session.getId());
        if (route != null) {
            WsEnvelope envelope = registry.get(route);
            if (envelope != null) {
                try {
                    PostOffice.getInstance().send(route, message, new Kv(WsEnvelope.TYPE, WsEnvelope.STRING),
                            new Kv(WsEnvelope.ROUTE, route), new Kv(WsEnvelope.TX_PATH, envelope.txPath));
                } catch (IOException e) {
                    log.error("Unable to route websocket message to {}, {}", route, e.getMessage());
                }
            }
        }
    }

    @OnMessage
    public void onBinary(byte[] payload, Session session) {
        String route = registry.getRoute(session.getId());
        if (route != null) {
            WsEnvelope envelope = registry.get(route);
            if (envelope != null) {
                try {
                    PostOffice.getInstance().send(route, payload, new Kv(WsEnvelope.TYPE, WsEnvelope.BYTES),
                            new Kv(WsEnvelope.ROUTE, route), new Kv(WsEnvelope.TX_PATH, envelope.txPath));

                } catch (IOException e) {
                    log.error("Unable to route websocket payload to {}, {}", route, e.getMessage());
                }
            }
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        this.open = false;
        this.session = null;
        this.disconnectTime = System.currentTimeMillis();
        String route = registry.getRoute(session.getId());
        if (route != null) {
            WsEnvelope envelope = registry.get(route);
            if (envelope != null) {
                try {
                    // Ensure handler is informed to release resources
                    PostOffice.getInstance().request(route, 5000, new Kv(WsEnvelope.ROUTE, route),
                            new Kv(WsEnvelope.TOKEN, envelope.origin),
                            new Kv(WsEnvelope.TYPE, WsEnvelope.CLOSE));
                    log.info("Session-{} {} closed ({}, {})", session.getId(), route,
                            reason.getCloseCode().getCode(), reason.getReasonPhrase());
                } catch (Exception e) {
                    log.error("Unable to close {} due to {}", route, e.getMessage());
                } finally {
                    registry.release(route);
                }
            }
        }
    }

    @OnError
    public void onError(Session session, Throwable error) {
        // log all errors except connection failure
        if (open) {
            log.warn("Session-{} {} exception {}", session.getId(), route, error.getMessage());
        }
    }

}