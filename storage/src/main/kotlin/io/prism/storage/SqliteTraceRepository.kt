package io.prism.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.prism.core.model.LlmCallTrace
import java.sql.ResultSet
import javax.sql.DataSource

class SqliteTraceRepository(
    private val dbPath: String = "prism_analytics.db",
    private val customDataSource: DataSource? = null
) : TraceRepository, AutoCloseable {

    private val dataSource: DataSource = customDataSource ?: createDefaultDataSource(dbPath)

    init {
        initSchema()
    }

    private fun createDefaultDataSource(path: String): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = if (path == ":memory:") "jdbc:sqlite::memory:" else "jdbc:sqlite:$path"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 5
            minimumIdle = 1
            isAutoCommit = true
        }
        return HikariDataSource(config)
    }

    private fun initSchema() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS traces (
                        id TEXT PRIMARY KEY,
                        timestamp INTEGER NOT NULL,
                        provider TEXT NOT NULL,
                        model TEXT NOT NULL,
                        prompt_tokens INTEGER NOT NULL,
                        completion_tokens INTEGER NOT NULL,
                        total_tokens INTEGER NOT NULL,
                        cached_tokens INTEGER NOT NULL,
                        duration_ms INTEGER NOT NULL,
                        ttft_ms INTEGER,
                        estimated_cost_usd REAL NOT NULL,
                        status_code INTEGER NOT NULL,
                        client_ip TEXT,
                        user_tag TEXT,
                        feature_tag TEXT,
                        streaming INTEGER NOT NULL,
                        prompt_preview TEXT,
                        completion_preview TEXT,
                        error TEXT
                    );
                    CREATE INDEX IF NOT EXISTS idx_traces_timestamp ON traces(timestamp);
                    CREATE INDEX IF NOT EXISTS idx_traces_model ON traces(model);
                    CREATE INDEX IF NOT EXISTS idx_traces_feature ON traces(feature_tag);
                """.trimIndent())
            }
        }
    }

    override fun save(trace: LlmCallTrace) {
        dataSource.connection.use { conn ->
            val sql = """
                INSERT OR REPLACE INTO traces (
                    id, timestamp, provider, model, prompt_tokens, completion_tokens,
                    total_tokens, cached_tokens, duration_ms, ttft_ms, estimated_cost_usd,
                    status_code, client_ip, user_tag, feature_tag, streaming,
                    prompt_preview, completion_preview, error
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, trace.id)
                ps.setLong(2, trace.timestamp)
                ps.setString(3, trace.provider)
                ps.setString(4, trace.model)
                ps.setInt(5, trace.promptTokens)
                ps.setInt(6, trace.completionTokens)
                ps.setInt(7, trace.totalTokens)
                ps.setInt(8, trace.cachedTokens)
                ps.setLong(9, trace.durationMs)
                if (trace.timeToFirstTokenMs != null) ps.setLong(10, trace.timeToFirstTokenMs) else ps.setNull(10, java.sql.Types.INTEGER)
                ps.setDouble(11, trace.estimatedCostUsd)
                ps.setInt(12, trace.statusCode)
                ps.setString(13, trace.clientIp)
                ps.setString(14, trace.userTag)
                ps.setString(15, trace.featureTag)
                ps.setInt(16, if (trace.streaming) 1 else 0)
                ps.setString(17, trace.promptPreview)
                ps.setString(18, trace.completionPreview)
                ps.setString(19, trace.error)
                ps.executeUpdate()
            }
        }
    }

    override fun findAll(limit: Int, offset: Int): List<LlmCallTrace> {
        val result = mutableListOf<LlmCallTrace>()
        dataSource.connection.use { conn ->
            val sql = "SELECT * FROM traces ORDER BY timestamp DESC LIMIT ? OFFSET ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, limit)
                ps.setInt(2, offset)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    result.add(mapRow(rs))
                }
            }
        }
        return result
    }

    override fun findSince(timestamp: Long): List<LlmCallTrace> {
        val result = mutableListOf<LlmCallTrace>()
        dataSource.connection.use { conn ->
            val sql = "SELECT * FROM traces WHERE timestamp >= ? ORDER BY timestamp DESC"
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, timestamp)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    result.add(mapRow(rs))
                }
            }
        }
        return result
    }

    override fun findByFeature(featureTag: String): List<LlmCallTrace> {
        val result = mutableListOf<LlmCallTrace>()
        dataSource.connection.use { conn ->
            val sql = "SELECT * FROM traces WHERE LOWER(feature_tag) = LOWER(?) ORDER BY timestamp DESC"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, featureTag)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    result.add(mapRow(rs))
                }
            }
        }
        return result
    }

    override fun count(): Long {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM traces")
                if (rs.next()) return rs.getLong(1)
            }
        }
        return 0L
    }

    override fun clear() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM traces")
            }
        }
    }

    override fun close() {
        if (dataSource is HikariDataSource) {
            dataSource.close()
        }
    }

    private fun mapRow(rs: ResultSet): LlmCallTrace {
        val rawTtft = rs.getLong("ttft_ms")
        val ttft = if (rs.wasNull()) null else rawTtft

        return LlmCallTrace(
            id = rs.getString("id"),
            timestamp = rs.getLong("timestamp"),
            provider = rs.getString("provider"),
            model = rs.getString("model"),
            promptTokens = rs.getInt("prompt_tokens"),
            completionTokens = rs.getInt("completion_tokens"),
            totalTokens = rs.getInt("total_tokens"),
            cachedTokens = rs.getInt("cached_tokens"),
            durationMs = rs.getLong("duration_ms"),
            timeToFirstTokenMs = ttft,
            estimatedCostUsd = rs.getDouble("estimated_cost_usd"),
            statusCode = rs.getInt("status_code"),
            clientIp = rs.getString("client_ip"),
            userTag = rs.getString("user_tag"),
            featureTag = rs.getString("feature_tag"),
            streaming = rs.getInt("streaming") == 1,
            promptPreview = rs.getString("prompt_preview"),
            completionPreview = rs.getString("completion_preview"),
            error = rs.getString("error")
        )
    }
}
