"""SQLite regression tests execute the SQL extracted from the production DAO.

The wiring checks are source contracts, not a substitute for running Room/JVM tests.
No device database or message content is opened by these tests.
"""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
DAO = ROOT / "app/src/main/kotlin/io/github/mangi/eta/data/db/ConversationDao.kt"
STORE = ROOT / "app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentConversationStore.kt"
HELPER = ROOT / "app/src/main/kotlin/io/github/mangi/eta/ui/app/ConversationMessageIdMigration.kt"


def query_for(method):
    source = DAO.read_text()
    match = re.search(r'@Query\(((?:(?!@Query\().)*?)\)\s*suspend fun ' + method + r'\(', source, re.S)
    if not match:
        raise AssertionError(f"production DAO query missing: {method}")
    return "".join(re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"', match.group(1)))


class ConversationMessageIdSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript("""
            CREATE TABLE conversations(id TEXT PRIMARY KEY);
            CREATE TABLE conversation_messages(
                id TEXT NOT NULL PRIMARY KEY,
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                sort_index INTEGER NOT NULL,
                type TEXT NOT NULL,
                content TEXT NOT NULL,
                UNIQUE(conversation_id, sort_index)
            );
            INSERT INTO conversations VALUES('a'), ('b');
        """)
        self.db.executemany("INSERT INTO conversation_messages VALUES(?,?,?,?,?)", [
            ("old-user", "a", 0, "user", "first retained"),
            ("old-supplement", "a", 1, "user", "supplement retained"),
            ("assistant", "a", 2, "assistant", "answer retained"),
        ])
        self.db.commit()
        self.rename_sql = query_for("renameMessageId")
        self.exists_sql = query_for("messageIdExists")

    def rows(self):
        return self.db.execute("SELECT * FROM conversation_messages ORDER BY conversation_id,sort_index").fetchall()

    def rename(self, old, new, owner="a"):
        return self.db.execute(self.rename_sql, {"conversationId": owner, "oldId": old, "newId": new}).rowcount

    def occupy(self, target, owner="b", index=0):
        self.db.execute("INSERT INTO conversation_messages VALUES(?,?,?,?,?)", (target, owner, index, "user", "other retained"))
        self.db.commit()

    def test_old_sql_reproduces_exact_primary_key_failure(self):
        self.occupy("user-turn")
        before = self.rows()
        with self.assertRaises(sqlite3.IntegrityError) as caught:
            with self.db:
                self.db.execute("UPDATE conversation_messages SET id=:newId WHERE conversation_id=:conversationId AND id=:oldId",
                    {"newId": "user-turn", "oldId": "old-user", "conversationId": "a"})
        if hasattr(caught.exception, "sqlite_errorcode"):
            self.assertEqual(1555, caught.exception.sqlite_errorcode)
        self.assertEqual(before, self.rows())

    def test_free_target_renames_only_identity_and_second_load_is_noop(self):
        before = self.rows()
        with self.db:
            self.assertEqual(1, self.rename("old-user", "user-turn"))
        after = self.rows()
        self.assertEqual(before[0][1:], after[0][1:])
        self.assertEqual("user-turn", after[0][0])
        self.assertEqual(before[1:], after[1:])
        with self.db:
            self.assertEqual(0, self.rename("old-user", "user-turn"))
        self.assertEqual(after, self.rows())

    def test_cross_conversation_collision_preserves_both_rows_on_repeated_load(self):
        self.occupy("user-turn")
        before = self.rows()
        for _ in range(2):
            with self.db:
                self.assertEqual(0, self.rename("old-user", "user-turn"))
            self.assertEqual(before, self.rows())
        self.assertEqual(1, self.db.execute(self.exists_sql, {"id": "user-turn"}).fetchone()[0])

    def test_same_conversation_collision_preserves_all_rows(self):
        self.occupy("user-turn", "a", 3)
        before = self.rows()
        with self.db:
            self.assertEqual(0, self.rename("old-user", "user-turn"))
        self.assertEqual(before, self.rows())

    def test_supplement_target_collision_is_also_guarded(self):
        self.occupy("user-turn-supplement-tail")
        before = self.rows()
        with self.db:
            self.assertEqual(0, self.rename("old-supplement", "user-turn-supplement-tail"))
        self.assertEqual(before, self.rows())

    def test_self_missing_source_and_wrong_conversation_are_noops(self):
        before = self.rows()
        with self.db:
            self.assertEqual(0, self.rename("old-user", "old-user"))
            self.assertEqual(0, self.rename("missing", "free"))
            self.assertEqual(0, self.rename("old-user", "free", "b"))
        self.assertEqual(before, self.rows())

    def test_unexpected_failure_rolls_back_previous_rename(self):
        before = self.rows()
        with self.assertRaises(RuntimeError):
            with self.db:
                self.assertEqual(1, self.rename("old-user", "user-turn"))
                self.assertEqual(0, self.rename("missing", "free"))
                raise RuntimeError("unexpected affected row count")
        self.assertEqual(before, self.rows())

    def test_production_wiring_checks_all_targets_before_writes_and_publishes_result(self):
        sql = self.rename_sql.upper()
        self.assertIn("NOT EXISTS", sql)
        self.assertNotIn("OR REPLACE", sql)
        self.assertNotIn("OR IGNORE", sql)
        self.assertNotIn("conversation_id", self.exists_sql)
        dao = DAO.read_text()
        self.assertRegex(dao, r'suspend fun renameMessageId\([^)]*\): Int')
        store = STORE.read_text()
        self.assertIn("ConversationMessageIdMigration.apply(", store)
        self.assertIn("targetExists = { dao.messageIdExists(it) }", store)
        self.assertIn("messages = identityMessages,", store)
        self.assertNotIn("identityMigration.renames.forEach", store)
        helper = HELPER.read_text()
        self.assertLess(helper.index("for (target in targets)"), helper.index("for ((oldId, newId) in moves)"))
        self.assertIn("if (targetExists(target)) return original", helper)
        self.assertIn("check(rename(oldId, newId) == 1)", helper)
        self.assertNotIn("catch (", helper)


if __name__ == "__main__":
    unittest.main()
