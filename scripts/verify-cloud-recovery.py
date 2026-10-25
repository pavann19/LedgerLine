"""Bounded operator acceptance on the disposable Azure host, never production.

Run after azure-evidence-run.ps1 has created /tmp/invariants.sql. Credentials
are read locally from Docker and never emitted. Existing k6 workloads are used
unchanged. A failed performance threshold remains a failed measurement.
"""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import urllib.parse
import uuid


def command(args, **kwargs):
    return subprocess.check_output(args, text=True, timeout=600, **kwargs).strip()


def main():
    destination = Path('/var/tmp/ledgerline-acceptance')
    destination.mkdir(exist_ok=True)
    config = json.loads(command(['docker', 'inspect', 'ledgerline-ledger-service']))[0]
    env = dict(x.split('=', 1) for x in config['Config']['Env'] if '=' in x)
    url = urllib.parse.urlsplit(env['SPRING_DATASOURCE_URL'].removeprefix('jdbc:'))
    db_env = {**os.environ, 'PGPASSWORD': env['SPRING_DATASOURCE_PASSWORD'], 'PGSSLMODE': 'require'}
    db_args = ['-h', url.hostname, '-U', env['SPRING_DATASOURCE_USERNAME']]
    report = {'source_image': config['Image'], 'events': []}

    def record(name, **values):
        item = {'check': name, **values}
        report['events'].append(item)
        print(json.dumps(item), flush=True)
        (destination / 'report.json').write_text(json.dumps(report, indent=2))

    def sql(query, database='ledgerline'):
        return command(['psql', '-X', '-At', '-v', 'ON_ERROR_STOP=1', *db_args, '-d', database,
                        '-c', query], env=db_env)

    def wait_for(predicate, timeout=180):
        start = time.monotonic()
        while time.monotonic() - start < timeout:
            if predicate():
                return round(time.monotonic() - start, 3)
            time.sleep(1)
        raise AssertionError('Recovery condition exceeded bounded timeout')

    def settled():
        return sql("SELECT (SELECT count(*) FROM postings)=(SELECT count(*) FROM statement_view) "
                   "AND NOT EXISTS(SELECT 1 FROM outbox WHERE published_at IS NULL)") == 't'

    def health(port):
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=3) as response:
                return json.load(response)['status'] == 'UP'
        except Exception:
            return False

    def token():
        def encode(value):
            return base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b'=')
        data = encode({'alg': 'HS256', 'typ': 'JWT'}) + b'.' + encode(
            {'sub': 'cloud-recovery-operator', 'roles': ['OPERATOR'], 'exp': int(time.time()) + 7200})
        return (data + b'.' + base64.urlsafe_b64encode(hmac.new(
            env['JWT_SECRET'].encode(), data, hashlib.sha256).digest()).rstrip(b'=')).decode()

    auth = token()

    def api(path, body, key=None):
        headers = {'Authorization': 'Bearer ' + auth, 'Content-Type': 'application/json'}
        if key:
            headers['Idempotency-Key'] = key
        request = urllib.request.Request('http://127.0.0.1:8080/api/v1/' + path,
                                         data=json.dumps(body).encode(), headers=headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            assert response.status in (200, 201)
            return json.load(response)

    def transfer(source, target, amount=1, key=None):
        return api('transfers', {'fromAccountId': source, 'toAccountId': target,
                                'amountMinor': amount, 'currency': 'USD'}, key or uuid.uuid4().hex)

    def invariants(database='ledgerline'):
        output = command(['psql', '-X', '-v', 'ON_ERROR_STOP=1', *db_args, '-d', database,
                          '-f', '/tmp/invariants.sql'], env=db_env)
        assert 'CLOUD_SQL_ASSERTIONS_PASSED' in output
        return output

    # This is a single dedicated host with explicit container CPU allocations,
    # not an independent load-generator topology or a saturation claim.
    command(['docker', 'pull', 'grafana/k6:latest'])
    funding = api('accounts', {'currency': 'USD', 'type': 'OVERDRAFT'})['id']
    accounts = [api('accounts', {'currency': 'USD', 'type': 'CUSTOMER'})['id'] for _ in range(2)]
    for account in accounts:
        transfer(funding, account, 1000000)
    load_env = {**os.environ, 'ACCESS_TOKEN': auth, 'ACCOUNT_A': accounts[0], 'ACCOUNT_B': accounts[1],
                'CLOUD_URL': 'http://127.0.0.1:8080/api/v1', 'K6_PROFILE': 'performance'}
    with (destination / 'performance.log').open('w') as log:
        result = subprocess.run(['docker', 'run', '--rm', '--network', 'host', '--cpus=0.5',
            '-e', 'ACCESS_TOKEN', '-e', 'ACCOUNT_A', '-e', 'ACCOUNT_B', '-e', 'CLOUD_URL',
            '-e', 'K6_PROFILE', '-v', '/tmp/k6-cloud-load.js:/bench/load.js:ro',
            '-v', f'{destination}:/evidence', 'grafana/k6:latest', 'run', '/bench/load.js',
            '--summary-export=/evidence/performance.json'], env=load_env, stdout=log, stderr=log, timeout=360)
    metrics = json.loads((destination / 'performance.json').read_text())['metrics']
    record('performance', threshold_exit_code=result.returncode,
           transfers=metrics.get('cloud_transfers_success'), failed_requests=metrics.get('http_req_failed'),
           latency=metrics.get('http_req_duration'), requests=metrics.get('http_reqs'),
           generator='same VM, separate 0.5 CPU container',
           generator_image=command(['docker', 'image', 'inspect', 'grafana/k6:latest', '--format', '{{.Id}}']))
    wait_for(settled)
    invariants()

    try:
        command(['docker', 'stop', 'ledgerline-projection-service'])
        before = int(sql('SELECT count(*) FROM statement_view'))
        for _ in range(20):
            transfer(accounts[0], accounts[1])
        assert int(sql('SELECT count(*) FROM statement_view')) == before
        assert int(sql('SELECT count(*) FROM postings')) == before + 40
        start = time.monotonic()
        command(['docker', 'start', 'ledgerline-projection-service'])
        wait_for(lambda: health(8081) and settled())
        record('projection_restart', transfers_while_stopped=20,
               recovery_seconds=round(time.monotonic() - start, 3))
        invariants()

        command(['docker', 'stop', 'ledgerline-kafka'])
        for _ in range(20):
            transfer(accounts[0], accounts[1])
        pending = int(sql('SELECT count(*) FROM outbox WHERE published_at IS NULL'))
        assert pending >= 20
        start = time.monotonic()
        command(['docker', 'start', 'ledgerline-kafka'])
        wait_for(settled)
        record('broker_restart', transfers_while_stopped=20, durable_pending_events=pending,
               recovery_seconds=round(time.monotonic() - start, 3))
        invariants()

        key = uuid.uuid4().hex
        original = transfer(accounts[0], accounts[1], key=key)
        wait_for(settled)
        start = time.monotonic()
        command(['docker', 'restart', 'ledgerline-ledger-service'])
        wait_for(lambda: health(8080))
        replay = transfer(accounts[0], accounts[1], key=key)
        assert replay['transactionId'] == original['transactionId'] and replay['idempotentReplay']
        record('ledger_restart_replay', recovery_seconds=round(time.monotonic() - start, 3),
               original_transaction_returned=True)
        wait_for(settled)
        invariants()

        def committed_offsets():
            description = command(['docker', 'exec', '-e', 'KAFKA_HEAP_OPTS=-Xms64m -Xmx128m', 'ledgerline-kafka',
                '/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'kafka:9092',
                '--describe', '--group', 'ledgerline-projection'])
            rows = [line.split() for line in description.splitlines()
                    if line.startswith('ledgerline-projection ') and 'ledger.transfers.v1' in line]
            assert len(rows) == 3, 'Expected all three topic partitions in consumer receipt'
            if any(row[3] != row[4] for row in rows):
                return -1
            return sum(int(row[3]) for row in rows)

        # Observe offset advancement as well as unchanged projection rows; a
        # delay alone would not demonstrate that the duplicate was consumed.
        wait_for(settled)
        wait_for(lambda: committed_offsets() >= 0)
        offsets_before = committed_offsets()
        projection_before = sql('SELECT count(*) FROM statement_view')
        processed_before = sql('SELECT count(*) FROM processed_events')
        payload = sql('SELECT payload::text FROM outbox ORDER BY id DESC LIMIT 1')
        assert payload
        command(['docker', 'exec', '-i', '-e', 'KAFKA_HEAP_OPTS=-Xms64m -Xmx128m', 'ledgerline-kafka',
                 '/opt/kafka/bin/kafka-console-producer.sh', '--bootstrap-server', 'kafka:9092',
                 '--topic', 'ledger.transfers.v1'], input=payload + '\n')
        wait_for(lambda: committed_offsets() >= offsets_before + 1)
        assert projection_before == sql('SELECT count(*) FROM statement_view')
        assert processed_before == sql('SELECT count(*) FROM processed_events')
        record('duplicate_event', committed_offset_advanced=True,
               statement_rows_unchanged=True, processed_event_count_unchanged=True)
        invariants()

        # Freeze both writers for a consistent logical backup; never claim online RPO/RTO.
        command(['docker', 'stop', 'ledgerline-ledger-service', 'ledgerline-projection-service'])
        tables = ['accounts', 'account_balances', 'transactions', 'postings', 'outbox', 'audit_log',
                  'processed_events', 'statement_view', 'daily_account_summary',
                  'flyway_schema_history', 'flyway_schema_history_projection']

        def fingerprints(database):
            return {table: sql(f"SELECT count(*) || ':' || md5(coalesce(string_agg(row_to_json(t)::text,"
                               f"E'\\n' ORDER BY row_to_json(t)::text),'')) FROM {table} t", database)
                    for table in tables}

        before = fingerprints('ledgerline')
        command(['docker', 'pull', 'postgres:16-alpine'])
        common = ['docker', 'run', '--rm', '--network', 'host', '-e', 'PGPASSWORD',
                  '-e', 'PGSSLMODE', '-v', f'{destination}:/evidence', 'postgres:16-alpine']
        start = time.monotonic()
        command([*common, 'pg_dump', *db_args, '-d', 'ledgerline', '-Fc', '-f', '/evidence/backup.dump'], env=db_env)
        dump_seconds = round(time.monotonic() - start, 3)
        restore_db = 'ledgerline_restore_' + uuid.uuid4().hex[:8]
        sql(f'CREATE DATABASE {restore_db}', 'postgres')
        start = time.monotonic()
        command([*common, 'pg_restore', *db_args, '-d', restore_db, '--no-owner', '--no-privileges',
                 '--exit-on-error', '/evidence/backup.dump'], env=db_env)
        restore_seconds = round(time.monotonic() - start, 3)
        assert before == fingerprints(restore_db)
        invariants(restore_db)
        record('backup_restore', mode='quiesced logical backup into separate database',
               dump_seconds=dump_seconds, restore_seconds=restore_seconds,
               backup_bytes=(destination / 'backup.dump').stat().st_size,
               matching_table_fingerprints=len(tables), postings=int(sql('SELECT count(*) FROM postings')))
    finally:
        command(['docker', 'start', 'ledgerline-kafka', 'ledgerline-ledger-service', 'ledgerline-projection-service'])

    wait_for(lambda: health(8080) and health(8081) and settled())
    final = invariants()
    (destination / 'final-invariants.txt').write_text(final)
    report['result'] = 'PASS' if result.returncode == 0 else 'RECOVERY_PASS_PERFORMANCE_THRESHOLD_FAILED'
    (destination / 'report.json').write_text(json.dumps(report, indent=2))
    print(json.dumps({'result': report['result']}), flush=True)


if __name__ == '__main__':
    main()
