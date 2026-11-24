"""Verify real Docker -> Alloy -> Loki -> Grafana delivery on a local Compose stack.

Creates one disposable passenger/trip and two log-only probe containers. Does not
print Grafana credentials. No third-party Python packages required.
"""
import base64
from contextlib import closing
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import sqlite3
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True, stderr=subprocess.PIPE).strip()


def http(url, method="GET", data=None, headers=None):
    encoded = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request(url, data=encoded, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)


def eventually(check, description, timeout=75):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except urllib.error.HTTPError as error:
            if error.code in (401, 403):
                raise RuntimeError("Grafana credentials were rejected; stop without retrying login") from error
            last_error = str(error)
        except (urllib.error.URLError, TimeoutError, AssertionError, subprocess.CalledProcessError) as error:
            last_error = str(error)
        time.sleep(2)
    raise AssertionError(f"{description}: {last_error or 'condition not reached'}")


def main():
    container = os.environ.get("LOGGING_GRAFANA_CONTAINER", "grafana")
    info = json.loads(docker("inspect", container))[0]
    project = info["Config"]["Labels"]["com.docker.compose.project"]
    grafana_password = os.environ.get("LOGGING_GRAFANA_PASSWORD")
    headers = {}
    if grafana_password:
        credentials = base64.b64encode((os.environ.get("LOGGING_GRAFANA_USER", "admin") + ":" + grafana_password).encode()).decode()
        headers["Authorization"] = "Basic " + credentials
    grafana = os.environ.get("LOGGING_GRAFANA_URL", "http://localhost:3000")
    gateway = os.environ.get("LOGGING_GATEWAY_URL", "http://localhost:8080")
    for url in (grafana, gateway):
        assert urllib.parse.urlsplit(url).hostname in ("localhost", "127.0.0.1"), "Only target a local demo stack"

    def gf(path):
        return http(grafana + path, headers=headers)

    if grafana_password:
        health = eventually(lambda: gf("/api/datasources/uid/loki/health"), "Grafana Loki datasource ready")
        assert health["status"] == "OK", health
        dashboard = gf("/api/dashboards/uid/ds03-logs")["dashboard"]
        verification_mode = "Authenticated Grafana API/proxy"
    else:
        # Read back the actual provisioned DB without changing an existing login.
        with tempfile.TemporaryDirectory(prefix="ds03-grafana-verify-") as directory:
            database = str(Path(directory) / "grafana.db")
            docker("cp", container + ":/var/lib/grafana/grafana.db", database)
            with closing(sqlite3.connect(database)) as connection:
                datasource = connection.execute("SELECT type,url FROM data_source WHERE uid='loki'").fetchone()
                assert datasource == ("loki", "http://loki:3100"), datasource
                row = connection.execute("SELECT data FROM dashboard WHERE uid='ds03-logs'").fetchone()
                assert row, "Logs dashboard not provisioned"
                dashboard = json.loads(row[0])
        eventually(lambda: docker("exec", container, "wget", "-qO-", "http://loki:3100/ready") == "ready", "Loki ready")
        verification_mode = "Provisioned Grafana DB readback + Loki queries from Grafana container (existing credentials unchanged)"
    assert dashboard["uid"] == "ds03-logs" and len(dashboard["panels"]) == 3
    print("PASS Loki ready, Grafana datasource and provisioned DS03 Logs dashboard", flush=True)

    started = time.time_ns() - 10_000_000_000

    def query(expression, instant=False):
        params = {"query": expression}
        if not instant:
            params.update(start=str(started), end=str(time.time_ns()), limit="1000", direction="forward")
        endpoint = "query" if instant else "query_range"
        suffix = "/loki/api/v1/" + endpoint + "?" + urllib.parse.urlencode(params)
        result = gf("/api/datasources/proxy/uid/loki" + suffix) if grafana_password else json.loads(docker("exec", container, "wget", "-qO-", "http://loki:3100" + suffix))
        assert result["status"] == "success"
        return result["data"]["result"]

    correlation = str(uuid.uuid4())
    request_headers = {"X-Correlation-ID": correlation}
    email = "logging." + correlation + "@ds03.test"
    password = "Logging@123456"
    http(gateway + "/api/auth/register/passenger", "POST", {"fullName": "Logging smoke passenger", "email": email, "password": password}, request_headers)
    login = http(gateway + "/api/auth/login", "POST", {"email": email, "password": password}, request_headers)
    request_headers["Authorization"] = "Bearer " + login["accessToken"]
    trip = http(gateway + "/api/trips", "POST", {
        "pickup": {"address": "Logging smoke pickup", "latitude": -30.0, "longitude": 70.0},
        "destination": {"address": "Logging smoke destination", "latitude": -29.98, "longitude": 70.02}
    }, request_headers)
    http(gateway + "/api/trips/" + trip["tripId"] + "/cancel", "POST", {"reason": "Logging smoke cleanup"}, request_headers)

    selector = '{compose_project=' + json.dumps(project) + ',service_name=~".+"}'
    trace_query = selector + " |= " + json.dumps(correlation)

    def find_trace():
        results = query(trace_query)
        services = {stream["stream"]["service_name"] for stream in results}
        return results if {"trip-service", "pricing-service"} <= services else None

    traces = eventually(find_trace, "Same real Correlation ID in Trip and Pricing logs")
    traced_services = sorted({stream["stream"]["service_name"] for stream in traces})
    print("PASS cross-service correlation", correlation, traced_services, flush=True)

    # Controlled samples prove multiline grouping and Compose-project exclusion.
    probe_id = str(uuid.uuid4())
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%S.000")
    sample = stamp + " ERROR 1 --- [probe] logging.smoke : [" + probe_id + "] LOGGING_SMOKE\njava.lang.IllegalStateException: controlled probe\n\tat logging.smoke.Probe.run(Probe.java:1)"
    names = ["ds03-logging-probe-" + probe_id[:8], "ds03-unrelated-probe-" + probe_id[:8]]
    try:
        for name, compose_project in zip(names, (project, "unrelated-" + probe_id)):
            docker("run", "-d", "--name", name, "--label", "com.docker.compose.project=" + compose_project,
                   "--label", "com.docker.compose.service=trip-service", "--entrypoint", "sh",
                   "postgres:16-alpine", "-c", 'printf "%s\\n" "$1"; sleep 120', "probe", sample)
        probe_query = selector + " |= " + json.dumps(probe_id)

        def find_probe():
            results = query(probe_query)
            lines = [(stream["stream"], entry[1]) for stream in results for entry in stream["values"]]
            return lines if any(labels.get("level") == "ERROR" and "\njava.lang.IllegalStateException" in line and "Probe.java:1" in line for labels, line in lines) else None

        probe_lines = eventually(find_probe, "ERROR label and multiline stack trace")
        assert all(labels.get("container") == names[0] for labels, _ in probe_lines)
        all_probe_logs = query('{service_name=~".+"} |= ' + json.dumps(probe_id))
        assert all(stream["stream"]["compose_project"] == project for stream in all_probe_logs)
        print("PASS severity ERROR, multiline grouping and unrelated-project exclusion", flush=True)

        # Validate the dashboard's three rendered LogQL expressions through Grafana.
        filters = {"$project": ".+", "$service": ".+", "$level": ".*", "${search:doublequote}": json.dumps(correlation), "$__auto": "1m", "$__range": "15m"}
        for panel in dashboard["panels"]:
            expression = panel["targets"][0]["expr"]
            for key, value in filters.items():
                expression = expression.replace(key, value)
            query(expression, instant=panel["type"] == "stat")
        print("PASS all three rendered dashboard LogQL queries", flush=True)

        # The record must remain queryable after a real collector restart.
        collector = os.environ.get("LOGGING_ALLOY_CONTAINER", "alloy")
        docker("restart", collector)
        eventually(find_trace, "Previously collected logs survive Alloy restart")
        docker("exec", names[0], "sh", "-c", 'printf "%s\\n" "$1" > /proc/1/fd/1', "probe",
               stamp + " INFO 1 --- [probe] logging.smoke : [" + probe_id + "] AFTER_COLLECTOR_RESTART")
        eventually(lambda: any("AFTER_COLLECTOR_RESTART" in entry[1] for stream in query(probe_query) for entry in stream["values"]),
                   "New logs are collected after Alloy restart")
        result = {"result": "PASS", "verification_mode": verification_mode, "project": project, "correlation_id": correlation,
                  "trip_id": trip["tripId"], "traced_services": traced_services,
                  "probe_id": probe_id, "dashboard_uid": "ds03-logs",
                  "verified": ["Loki ready", "provisioned Grafana Loki datasource and dashboard", "real cross-service correlation", "ERROR label", "multiline Java stack", "Compose project isolation", "all panel queries", "logs remain queryable after Alloy restart", "new logs are collected after Alloy restart"]}
        output = Path("docs/acceptance/2026-11-23-centralized-logging.json")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print("PASS Alloy restart; evidence:", output, flush=True)
    finally:
        for name in names:
            subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
