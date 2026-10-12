"""Regression checks against the actual Room SQL, using an in-memory SQLite DB.
Run with: python3 -m unittest discover -s tests -v
"""
import re
import sqlite3
import unittest
from pathlib import Path

SOURCE = (Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/id/bundasiaga/app/data/Db.kt').read_text()


def query(method):
    return re.search(r'@Query\("([^"\n]+)"\)\s+suspend fun ' + method + r'\(', SOURCE).group(1)


class SyncQueriesTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE incoming_case (id INTEGER PRIMARY KEY, receivedAt INTEGER, ackAt INTEGER, ackStatus TEXT, ackOutboxId INTEGER, duplicates INTEGER, syncedAt INTEGER)')
        self.db.execute('INSERT INTO incoming_case VALUES (1, 100, NULL, NULL, NULL, 0, NULL)')

    def mark_uploaded_snapshot(self, ack_at=None, ack_status=None, duplicates=0):
        self.db.execute(query('markSynced'), dict(id=1, at=500, ackAt=ack_at, ackStatus=ack_status, duplicates=duplicates))

    def pending(self):
        return self.db.execute(query('unsynced')).fetchall()

    def test_unchanged_snapshot_is_marked_synced(self):
        self.mark_uploaded_snapshot()
        self.assertEqual([], self.pending())

    def test_ack_received_during_upload_remains_pending(self):
        self.db.execute(query('recordAck'), dict(id=1, status='SIAP', at=200, outboxId=8))
        self.mark_uploaded_snapshot()
        self.assertEqual(1, len(self.pending()))
        self.mark_uploaded_snapshot(200, 'SIAP')
        self.assertEqual([], self.pending())

    def test_duplicate_received_during_upload_preserves_ack_and_remains_pending(self):
        self.db.execute(query('recordAck'), dict(id=1, status='SIAP', at=200, outboxId=8))
        self.db.execute(query('updateDuplicates'), dict(id=1, duplicates=1))
        self.mark_uploaded_snapshot(200, 'SIAP', 0)
        self.assertEqual(1, len(self.pending()))
        self.assertEqual(('SIAP', 1), self.db.execute('SELECT ackStatus, duplicates FROM incoming_case').fetchone())

    def test_new_ack_invalidates_sync_even_when_clock_moves_backwards(self):
        self.db.execute(query('updateDuplicates'), dict(id=1, duplicates=3))
        self.mark_uploaded_snapshot(duplicates=3)
        self.db.execute(query('recordAck'), dict(id=1, status='PENUH', at=50, outboxId=9))
        self.assertEqual(1, len(self.pending()))
        self.assertEqual(3, self.db.execute('SELECT duplicates FROM incoming_case').fetchone()[0])


if __name__ == '__main__':
    unittest.main()
