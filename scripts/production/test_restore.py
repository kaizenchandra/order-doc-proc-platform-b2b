from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import restore_local


class RestoreIsolationTests(unittest.TestCase):
    def test_failed_restore_removes_only_the_created_copy(self):
        calls = []
        def execute(service, command, *args, **kwargs):
            calls.append((command, args))
            if command == 'pg_restore': raise subprocess.CalledProcessError(1, command)
        with tempfile.TemporaryDirectory() as directory, patch.object(restore_local, 'ROOT', Path(directory)), \
                patch.object(restore_local, 'execute', side_effect=execute):
            with self.assertRaises(subprocess.CalledProcessError):
                restore_local.restore('order-db', 'orders', ('orders',))
            self.assertEqual([], list((Path(directory) / 'target').iterdir()))
        created = next(args[-1] for command, args in calls if command == 'createdb')
        dropped = next(args[-1] for command, args in calls if command == 'dropdb')
        self.assertTrue(created.startswith('restore_drill_'))
        self.assertEqual(created, dropped)
        self.assertNotEqual('orders', dropped)

    def test_failed_creation_never_drops_an_existing_database(self):
        calls = []
        def execute(service, command, *args, **kwargs):
            calls.append(command)
            if command == 'createdb': raise subprocess.CalledProcessError(1, command)
        with tempfile.TemporaryDirectory() as directory, patch.object(restore_local, 'ROOT', Path(directory)), \
                patch.object(restore_local, 'execute', side_effect=execute):
            with self.assertRaises(subprocess.CalledProcessError):
                restore_local.restore('order-db', 'orders', ('orders',))
        self.assertNotIn('dropdb', calls)
        self.assertNotIn('pg_restore', calls)


if __name__ == '__main__':
    unittest.main()
