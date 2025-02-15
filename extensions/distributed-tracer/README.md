# Distributed tracer sample application

This is a sample application for the distributed trace aggregator.

DO NOT use this for production. It is meant to be used as a demo app to illustrate how to aggregate trace metrics.
For production, please write your own custom aggregator.

This application subscribes to the route "distributed.trace.processor" to receive distributed trace information.

In addition to printing the trace information as log messages, it is a websocket server for distributed trace
UI applications to connect.

# IMPORTANT - distributed trace logging vs trace aggregator

Distributed trace logging should be set as INFO so that the performance metrics information are logged to a centralized
logging system such as Splunk. This would allow devops team to do production triage. The non-blocking operation
does not impact production traffic.

However, when distributed trace aggregator is deployed, the system will generate additional network traffic for
sending performance metrics to the aggregator.

If you build your own trace aggregator, please ensure that you save the metrics into a dedicated database to avoid
competing with production traffic.

## Turning on tracing

Distributed traces are initiated at the edge by the REST automation system.

To enable distributed trace, please set "tracing=true" for the REST endpoints in the "rest.yaml" file that
you want to trace. For details, please refer to the REST automation application subproject in the "extensions" packages.

## Transaction journaling

Optionally, you may enable transaction journaling for selected services. To enable journaling, you can define
the service routes in journal config YAML file. Journaling is a superset of distributed trace. You would need
to write your own distributed trace aggregator.

## Demo tracer HTML page

To demonstrate how to integrate with this distributed trace aggregator, you can deploy this application.

In a localhost environment, you can visit http://127.0.0.1:8300/trace.html.
In a cloud deployment, the URL will be defined by the cloud administrator.

It will return a sample HTML page that connects to the aggregator's websocket service port and
display the tracing information in real-time.

## Sample trace metrics

The following is a sample output when the browser hits the "hello.world" service provided by a python service.
The trace shows that the event passes through 3 services: "hello.world" at the language-connector,
"hello.world" service in python script and "async.http.response" by the rest-automation system.

```
{
  "trace": {
    "path": "GET /api/hello/world",
    "service": "async.http.response",
    "success": true,
    "origin": "2020051088c413a3a33c4d6082be287b1d51a0d8",
    "start": "2020-05-10T23:44:19.290Z",
    "exec_time": 0.418,
    "id": "fee3d82fd3dd47fc883aefb61f2f2fe8"
  },
  "annotations": {},
  "type": "trace"
}
{
  "trace": {
    "path": "GET /api/hello/world",
    "service": "hello.world",
    "success": true,
    "origin": "py0356ba1413324686b2828439634a4d37",
    "start": "2020-05-10T23:44:19.283Z",
    "exec_time": 0.191,
    "id": "fee3d82fd3dd47fc883aefb61f2f2fe8"
  },
  "annotations": {},
  "type": "trace"
}
{
  "trace": {
    "path": "GET /api/hello/world",
    "service": "hello.world",
    "success": true,
    "origin": "202005109b77436f7d1141078fd1a6d65b2bd7bf",
    "start": "2020-05-10T23:44:19.278Z",
    "exec_time": 0.218,
    "id": "fee3d82fd3dd47fc883aefb61f2f2fe8"
  },
  "annotations": {
    "version": "language-connector 1.12.41",
    "target": "py0356ba1413324686b2828439634a4d37"
  },
  "type": "trace"
}
```

## UI application

You may implement a UI tracer to connect to the aggregator's websocket service port to collect the tracing information.

Visualization is usually done by ingesting the raw tracing metrics information to an external DevOps tool
such as Graphite or Grafana.

If you want to do your own visualization, you may implement a single page application (React, Angular, etc.)
to render and filter the tracing metrics data.

