package com.example.tripservice.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "M2_TEST_DB_URL", matches = ".+")
class TripMigrationTest {
    @Test
    void payosUpgradeBackfillsAndKeepsOldInsertsCompatible() throws Exception {
        String schema = "payos_trip_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getenv("M2_TEST_DB_URL"), "m2_test", "m2_test");
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                statement.execute("SET search_path TO " + schema);
                statement.execute("CREATE TABLE trips (trip_id UUID PRIMARY KEY)");
                UUID oldTrip = UUID.randomUUID();
                statement.execute("INSERT INTO trips(trip_id) VALUES ('" + oldTrip + "')");
                String sql = Files.readString(Path.of("..", "..", "scripts", "db",
                    "2026-11-24-payos-trip.sql"));
                statement.execute(sql);
                statement.execute(sql);
                statement.execute("INSERT INTO trips(trip_id) VALUES ('" + UUID.randomUUID() + "')");
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM trips WHERE payment_provider='MOCK'")) {
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt(1));
                }
                statement.execute("UPDATE trips SET payment_provider='PAYOS' WHERE trip_id='" + oldTrip + "'");
                assertThrows(java.sql.SQLException.class,
                    () -> statement.execute("UPDATE trips SET payment_provider='CASH'"));
                assertThrows(java.sql.SQLException.class,
                    () -> statement.execute("UPDATE trips SET payment_provider=NULL"));
            }
            finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void upgradesOldHistoryChecksToAcceptAssigning() throws Exception {
        String schema = "m2_trip_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getenv("M2_TEST_DB_URL"), "m2_test", "m2_test");
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                statement.execute("SET search_path TO " + schema);
                statement.execute("CREATE TABLE trips (trip_id UUID PRIMARY KEY, status VARCHAR(30) "
                    + "CONSTRAINT legacy_trip_status_enum CHECK (status IN ('SEARCHING','ASSIGNED','CANCELLED')))");
                statement.execute("CREATE TABLE trip_status_history (history_id UUID PRIMARY KEY, "
                    + "from_status VARCHAR(30) CONSTRAINT legacy_history_from_enum "
                    + "CHECK (from_status IN ('SEARCHING','ASSIGNED','CANCELLED')), "
                    + "to_status VARCHAR(30) CONSTRAINT legacy_history_to_enum "
                    + "CHECK (to_status IN ('SEARCHING','ASSIGNED','CANCELLED')))");
                String sql = Files.readString(Path.of("..", "..", "scripts", "db",
                    "2026-11-19-m2-w5-trip.sql"));
                statement.execute(sql);
                UUID trip = UUID.randomUUID();
                statement.execute("INSERT INTO trips(trip_id,status) VALUES ('" + trip + "','ASSIGNING')");
                statement.execute("INSERT INTO trip_status_history(history_id,from_status,to_status) VALUES ('"
                    + UUID.randomUUID() + "','SEARCHING','ASSIGNING')");
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM trip_status_history WHERE to_status='ASSIGNING'")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
            }
            finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
