using Microsoft.Data.Sqlite;
using System;
using System.Collections.Generic;
using System.IO;

namespace WindowsAgent
{
    /// <summary>
    /// Local SQLite clipboard journal for the Windows agent.
    /// Mirrors the Android Room schema for clipboard_items.
    /// </summary>
    public class ClipboardDatabase
    {
        private const int MaxItems = 10;
        private readonly string _connectionString;

        public ClipboardDatabase()
        {
            // Store DB alongside the executable
            var dbPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "sync_database.db");
            _connectionString = $"Data Source={dbPath}";
            InitializeDatabase();
        }

        private void InitializeDatabase()
        {
            using var connection = new SqliteConnection(_connectionString);
            connection.Open();

            var cmd = connection.CreateCommand();
            cmd.CommandText = @"
                CREATE TABLE IF NOT EXISTS clipboard_items (
                    clipboardId TEXT PRIMARY KEY,
                    source      TEXT NOT NULL,
                    content     TEXT NOT NULL,
                    timestamp   INTEGER NOT NULL
                );
            ";
            cmd.ExecuteNonQuery();
        }

        /// <summary>
        /// Insert a clipboard item, enforcing the 10-item cap.
        /// Returns true if the item was new, false if it was a duplicate.
        /// </summary>
        public bool InsertItem(string clipboardId, string source, string content, long timestamp)
        {
            using var connection = new SqliteConnection(_connectionString);
            connection.Open();

            // Loop prevention: check if this ID already exists
            var checkCmd = connection.CreateCommand();
            checkCmd.CommandText = "SELECT COUNT(*) FROM clipboard_items WHERE clipboardId = @id";
            checkCmd.Parameters.AddWithValue("@id", clipboardId);
            var exists = Convert.ToInt64(checkCmd.ExecuteScalar()) > 0;
            if (exists) return false;

            // Insert
            var insertCmd = connection.CreateCommand();
            insertCmd.CommandText = @"
                INSERT OR REPLACE INTO clipboard_items (clipboardId, source, content, timestamp)
                VALUES (@id, @src, @content, @ts)
            ";
            insertCmd.Parameters.AddWithValue("@id", clipboardId);
            insertCmd.Parameters.AddWithValue("@src", source);
            insertCmd.Parameters.AddWithValue("@content", content);
            insertCmd.Parameters.AddWithValue("@ts", timestamp);
            insertCmd.ExecuteNonQuery();

            // Enforce 10-item cap
            EnforceCap(connection);

            return true;
        }

        /// <summary>
        /// Check if a clipboard ID already exists (for loop prevention).
        /// </summary>
        public bool ExistsById(string clipboardId)
        {
            using var connection = new SqliteConnection(_connectionString);
            connection.Open();
            var cmd = connection.CreateCommand();
            cmd.CommandText = "SELECT COUNT(*) FROM clipboard_items WHERE clipboardId = @id";
            cmd.Parameters.AddWithValue("@id", clipboardId);
            return Convert.ToInt64(cmd.ExecuteScalar()) > 0;
        }

        /// <summary>
        /// Get all clipboard items, newest first.
        /// </summary>
        public List<ClipboardEntry> GetAllItems()
        {
            var items = new List<ClipboardEntry>();
            using var connection = new SqliteConnection(_connectionString);
            connection.Open();

            var cmd = connection.CreateCommand();
            cmd.CommandText = "SELECT clipboardId, source, content, timestamp FROM clipboard_items ORDER BY timestamp DESC";
            using var reader = cmd.ExecuteReader();
            while (reader.Read())
            {
                items.Add(new ClipboardEntry
                {
                    ClipboardId = reader.GetString(0),
                    Source = reader.GetString(1),
                    Content = reader.GetString(2),
                    Timestamp = reader.GetInt64(3)
                });
            }
            return items;
        }

        private void EnforceCap(SqliteConnection connection)
        {
            var countCmd = connection.CreateCommand();
            countCmd.CommandText = "SELECT COUNT(*) FROM clipboard_items";
            var count = Convert.ToInt64(countCmd.ExecuteScalar());

            while (count > MaxItems)
            {
                var deleteCmd = connection.CreateCommand();
                deleteCmd.CommandText = @"
                    DELETE FROM clipboard_items 
                    WHERE clipboardId = (
                        SELECT clipboardId FROM clipboard_items ORDER BY timestamp ASC LIMIT 1
                    )
                ";
                deleteCmd.ExecuteNonQuery();
                count--;
            }
        }
    }

    public class ClipboardEntry
    {
        public string ClipboardId { get; set; } = "";
        public string Source { get; set; } = "";
        public string Content { get; set; } = "";
        public long Timestamp { get; set; }
    }
}
