"""Restore local PostgreSQL logical backups into disposable databases, never over the source."""
import json
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
DATABASES = {
    'order-db': ('orders', ('orders', 'order_documents', 'outbox_events', 'inbox_events')),
    'notification-db': ('notifications', ('inbox_events', 'audit_records', 'notification_records')),
}


def execute(service, *args, **kwargs):
    return subprocess.run(['docker', 'compose', 'exec', '-T', service, *args], cwd=ROOT,
                          check=True, stderr=subprocess.PIPE, timeout=120, **kwargs)


def restore(service, source, tables):
    restored = 'restore_drill_' + uuid.uuid4().hex
    created = False
    started = time.monotonic()
    (ROOT / 'target').mkdir(exist_ok=True)
    try:
        # TemporaryDirectory is private (0700); backups are deleted after the drill.
        with tempfile.TemporaryDirectory(prefix='restore-', dir=ROOT / 'target') as directory:
            archive = Path(directory) / 'database.dump'
            with archive.open('wb') as output:
                execute(service, 'pg_dump', '-U', source, '-d', source, '-Fc', '--no-owner', stdout=output)
            execute(service, 'createdb', '-U', source, '--template=template0', restored, stdout=subprocess.PIPE)
            created = True
            with archive.open('rb') as backup:
                execute(service, 'pg_restore', '-U', source, '-d', restored, '--exit-on-error',
                        '--single-transaction', '--no-owner', '--no-privileges', stdin=backup, stdout=subprocess.PIPE)
            counts = {}
            for table in tables:
                # Table names are constants; the target database name is generated, never an input.
                result = execute(service, 'psql', '-X', '-U', source, '-d', restored, '-v', 'ON_ERROR_STOP=1',
                                 '-tAc', f'SELECT count(*) FROM {table}', stdout=subprocess.PIPE)
                counts[table] = int(result.stdout.strip())
            if any(count == 0 for count in counts.values()):
                raise ValueError('Run the local smoke workflow first; restored tables must contain evidence')
            result = execute(service, 'psql', '-X', '-U', source, '-d', restored, '-v', 'ON_ERROR_STOP=1', '-tAc',
                             "SELECT count(*) FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND NOT convalidated",
                             stdout=subprocess.PIPE)
            if int(result.stdout.strip()) != 0:
                raise ValueError('Restored constraints are not validated')
            return {'database': source, 'restored_rows': counts, 'logical_restore_seconds': round(time.monotonic() - started, 3)}
    finally:
        if created:
            execute(service, 'dropdb', '-U', source, restored, stdout=subprocess.PIPE)


def main():
    results = [restore(service, source, tables) for service, (source, tables) in DATABASES.items()]
    print(json.dumps({'mode': 'local-logical-restore', 'results': results,
                      'temporary_databases_removed': True}, indent=2))


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        # Captured pg_restore stderr may include row contents; never print it.
        print(json.dumps({'failed': type(failure).__name__,
                          'action': 'Inspect local database health and restore_drill_ databases; no source was replaced'}))
        raise SystemExit(1) from None
