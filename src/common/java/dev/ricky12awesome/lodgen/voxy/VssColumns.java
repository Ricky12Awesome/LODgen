package dev.ricky12awesome.lodgen.voxy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Durable canonical VSS columns. Unlike VSS's cache, these have no backing MCA file. */
public final class VssColumns implements AutoCloseable {
    public record Column(String dimension, long position, byte[] frame, int size, long stamp, long revision) {
        public Column(String dimension, long position, byte[] frame, int size, long stamp) {
            this(dimension, position, frame, size, stamp, 0);
        }
    }
    private record Key(String dimension, long position) {}
    private record Region(String dimension, int x, int z) {}
    // Conservative coverage: deletions may retain a region until restart. This avoids
    // letting vanilla-only freshness checks validate LOD bytes they never observed.
    private final Set<Region> regions = new HashSet<>();
    // Only in-flight snapshots retain tokens, so generation distance does not grow this map.
    private final Map<Key, Set<Long>> snapshots = new HashMap<>();
    private long revision;
    private final Connection connection;

    public VssColumns(DataSource source, String identity) throws SQLException {
        connection = source.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("CREATE TABLE IF NOT EXISTS identity (value TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS columns (dimension TEXT NOT NULL, position INTEGER NOT NULL, frame BLOB NOT NULL, size INTEGER NOT NULL, stamp INTEGER NOT NULL, PRIMARY KEY(dimension, position)) WITHOUT ROWID");
            try (var rows = statement.executeQuery("SELECT value FROM identity")) {
                if (!rows.next() || !identity.equals(rows.getString(1))) {
                    // Wire format, block/biome identities and x-ray policy must match.
                    clear();
                    try (var update = connection.prepareStatement("INSERT INTO identity VALUES (?)")) {
                        statement.executeUpdate("DELETE FROM identity");
                        update.setString(1, identity);
                        update.executeUpdate();
                    }
                }
            }
            try (var rows = statement.executeQuery("SELECT DISTINCT dimension, position >> 37, (position << 32) >> 37 FROM columns")) {
                while (rows.next()) regions.add(new Region(rows.getString(1), rows.getInt(2), rows.getInt(3)));
            }
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
    }

    public synchronized Column get(String dimension, long position) throws SQLException {
        if (connection.isClosed()) return null;
        try (var query = connection.prepareStatement("SELECT frame, size, stamp FROM columns WHERE dimension=? AND position=?")) {
            query.setString(1, dimension);
            query.setLong(2, position);
            try (var rows = query.executeQuery()) {
                return rows.next() ? new Column(dimension, position, rows.getBytes(1), rows.getInt(2), rows.getLong(3)) : null;
            }
        }
    }

    public synchronized long acquire(String dimension, long position) {
        long token = ++revision;
        snapshots.computeIfAbsent(new Key(dimension, position), ignored -> new HashSet<>()).add(token);
        return token;
    }

    public synchronized boolean hasRegion(String dimension, int x, int z) {
        return regions.contains(new Region(dimension, x, z));
    }

    public synchronized void release(String dimension, long position, long token) {
        var key = new Key(dimension, position);
        var pending = snapshots.get(key);
        if (pending != null && pending.remove(token) && pending.isEmpty()) snapshots.remove(key);
    }

    /** Complete the transaction before LODgen checkpoints the batch as finished. */
    public synchronized List<Column> put(List<Column> columns) throws SQLException {
        var candidates = new java.util.ArrayList<Column>();
        var committed = new java.util.ArrayList<Column>();
        connection.setAutoCommit(false);
        try (var update = connection.prepareStatement("INSERT INTO columns VALUES (?,?,?,?,?) ON CONFLICT(dimension,position) DO UPDATE SET frame=excluded.frame,size=excluded.size,stamp=excluded.stamp WHERE excluded.stamp>=columns.stamp")) {
            for (var column : columns) {
                if (column.revision != 0 && !snapshots.getOrDefault(new Key(column.dimension, column.position), Set.of()).contains(column.revision))
                    continue; // A normal terrain edit overtook this snapshot.
                update.setString(1, column.dimension);
                update.setLong(2, column.position);
                update.setBytes(3, column.frame);
                update.setInt(4, column.size);
                update.setLong(5, column.stamp);
                update.addBatch();
                candidates.add(column);
            }
            int[] counts = update.executeBatch();
            for (int i = 0; i < counts.length; i++)
                if (counts[i] > 0 || counts[i] == java.sql.Statement.SUCCESS_NO_INFO) committed.add(candidates.get(i));
            connection.commit();
            for (var column : committed) regions.add(new Region(column.dimension,
                    (int) (column.position >> 32) >> 5, (int) column.position >> 5));
            return committed;
        } catch (SQLException error) {
            connection.rollback();
            throw error;
        } finally {
            for (var column : columns) release(column.dimension, column.position, column.revision);
            connection.setAutoCommit(true);
        }
    }

    public synchronized void delete(String dimension, long[] positions) throws SQLException {
        if (connection.isClosed()) return;
        try (var update = connection.prepareStatement("DELETE FROM columns WHERE dimension=? AND position=?")) {
            for (long position : positions) {
                snapshots.remove(new Key(dimension, position));
                update.setString(1, dimension);
                update.setLong(2, position);
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    public synchronized void clear() throws SQLException {
        snapshots.clear();
        if (connection.isClosed()) return;
        try (var statement = connection.createStatement()) { statement.executeUpdate("DELETE FROM columns"); }
    }

    @Override public synchronized void close() throws SQLException { connection.close(); }
}
