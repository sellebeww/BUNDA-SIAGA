"""Execute Room's generated migration against the exported v2 schema.

Build :app:assemblePilotDebug before running these checks.
This validates SQLite schema/data preservation, not the SQLCipher Android driver.
"""
import json
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / 'app/schemas/id.bundasiaga.app.data.BundaDb'
MIGRATION = ROOT / 'app/build/generated/ksp/pilotDebug/kotlin/id/bundasiaga/app/data/BundaDb_AutoMigration_2_3_Impl.kt'


class DatabaseMigrationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        old = json.loads((SCHEMAS / '2.json').read_text())['database']
        for table in old['entities']:
            self.db.execute(table['createSql'].replace('${TABLE_NAME}', table['tableName']))
            for index in table.get('indices', []):
                self.db.execute(index['createSql'].replace('${TABLE_NAME}', table['tableName']))
        self.db.execute("INSERT INTO exam (id, createdAt, patientCode, inputJson, level, firedRules, rulesVersion, outboxId) VALUES (7, 100, 'AB01', '{}', 'MERAH', 'H01', 'old-version', 9)")
        for statement in re.findall(r'connection\.execSQL\("(.*?)"\)', MIGRATION.read_text()):
            self.db.execute(statement)
        self.db.commit()

    def test_upgrade_preserves_saved_result_and_referral_link(self):
        self.assertEqual(('AB01', 'MERAH', 'H01', 'old-version', 9, None),
                         self.db.execute('SELECT patientCode, level, firedRules, rulesVersion, outboxId, resultJson FROM exam WHERE id = 7').fetchone())

    def test_migrated_columns_match_fresh_schema(self):
        new = json.loads((SCHEMAS / '3.json').read_text())['database']
        for table in new['entities']:
            columns = {r[1]: (r[2], bool(r[3])) for r in self.db.execute('PRAGMA table_info(`' + table['tableName'] + '`)')}
            expected = {f['columnName']: (f['affinity'], f.get('notNull', False)) for f in table['fields']}
            self.assertEqual(expected, columns, table['tableName'])

    def test_failed_result_save_keeps_the_unfinished_session(self):
        self.db.execute("INSERT INTO exam_session VALUES (1, '{}', 'AB', 4, 'v1', 200)")
        self.db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute('DELETE FROM exam_session')
                self.db.execute("INSERT INTO exam (id) VALUES (7)")
        self.assertEqual((4,), self.db.execute('SELECT questionIndex FROM exam_session WHERE id = 1').fetchone())


if __name__ == '__main__':
    unittest.main()
