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
import threading
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
    report['workload_sha256'] = hashlib.sha256(Path('/tmp/k6-cloud-load.js').read_bytes()).hexdigest()
    report['probe_sha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    options_path = Path('/tmp/acceptance-options.json')
    options = json.loads(options_path.read_text(encoding='utf-8-sig')) if options_path.exists() else {}
    repeats = options.get('performance_repeats', 1)
    assert isinstance(repeats, int) and 1 <= repeats <= 3

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

    # Persistent psql timing excludes connection setup and provides a bounded
    # client/server roundtrip diagnostic. It is not an application latency metric.
    timing = command(['psql', '-X', '-At', '-v', 'ON_ERROR_STOP=1', *db_args, '-d', 'ledgerline'],
                     input='\\timing on\n' + 'SELECT 1;\n' * 30, env=db_env)
    (destination / 'database-roundtrip-timing.txt').write_text(timing)

    # This is a single dedicated host with explicit container CPU allocations,
    # not an independent load-generator topology or a saturation claim.
    command(['docker', 'pull', 'grafana/k6:latest'])
    funding = api('accounts', {'currency': 'USD', 'type': 'OVERDRAFT'})['id']
    accounts = [api('accounts', {'currency': 'USD', 'type': 'CUSTOMER'})['id'] for _ in range(2)]
    for account in accounts:
        transfer(funding, account, 1000000)
    load_exit_codes = []
    cases = [('correctness', 'correctness')] + [
        ('performance' if i == 0 else f'performance-repeat-{i + 1}', 'performance') for i in range(repeats)]
    for case, profile in cases:
        load_env = {**os.environ, 'ACCESS_TOKEN': auth, 'ACCOUNT_A': accounts[0], 'ACCOUNT_B': accounts[1],
                    'CLOUD_URL': 'http://127.0.0.1:8080/api/v1', 'K6_PROFILE': profile,
                    'K6_SUMMARY_TREND_STATS': 'avg,min,med,max,p(90),p(95),p(99)'}
        stop_sampling = threading.Event()

        def sample_resources():
            with (destination / f'resource-samples-{case}.jsonl').open('w') as samples:
                while not stop_sampling.is_set():
                    try:
                        stats = command(['docker', 'stats', '--no-stream', '--format', '{{json .}}'])
                        samples.write(json.dumps({'utc_epoch': time.time(), 'host_load': os.getloadavg(),
                                                  'docker_stats': stats}) + '\n')
                        samples.flush()
                    except Exception as error:
                        samples.write(json.dumps({'observer_error': str(error)}) + '\n')
                    stop_sampling.wait(5)

        sampler = threading.Thread(target=sample_resources, daemon=True)
        sampler.start()
        try:
            with (destination / f'{case}.log').open('w') as log:
                result = subprocess.run(['docker', 'run', '--rm', '--network', 'host', '--cpus=0.5',
                    '--user', f'{os.getuid()}:{os.getgid()}',
                    '-e', 'ACCESS_TOKEN', '-e', 'ACCOUNT_A', '-e', 'ACCOUNT_B', '-e', 'CLOUD_URL',
                    '-e', 'K6_PROFILE', '-e', 'K6_SUMMARY_TREND_STATS',
                    '-v', '/tmp/k6-cloud-load.js:/bench/load.js:ro',
                    '-v', f'{destination}:/evidence', 'grafana/k6:latest', 'run', '/bench/load.js',
                    f'--summary-export=/evidence/{case}.json'], env=load_env, stdout=log, stderr=log, timeout=360)
        finally:
            stop_sampling.set()
            sampler.join(timeout=5)
        metrics = json.loads((destination / f'{case}.json').read_text())['metrics']
        record(case, profile=profile, threshold_exit_code=result.returncode,
               transfers=metrics.get('cloud_transfers_success'), failed_requests=metrics.get('http_req_failed'),
               latency=metrics.get('http_req_duration'), requests=metrics.get('http_reqs'),
               generator='same VM, separate 0.5 CPU container',
               generator_image=command(['docker', 'image', 'inspect', 'grafana/k6:latest', '--format', '{{.Id}}']))
        load_exit_codes.append(result.returncode)
        stats = json.loads(sql("SELECT row_to_json(s) FROM (SELECT numbackends,xact_commit,xact_rollback,"
            "blks_read,blks_hit,temp_bytes,deadlocks,blk_read_time,blk_write_time "
            "FROM pg_stat_database WHERE datname=current_database()) s"))
        record('database_statistics', case=case, values=stats)
        try:
            catchup = wait_for(settled)
            record('load_catchup', case=case, passed=True, observed_seconds=catchup, deadline_seconds=180)
        except AssertionError:
            record('load_catchup', case=case, passed=False, deadline_seconds=180,
                   ledger_rows=int(sql('SELECT count(*) FROM postings')),
                   projection_rows=int(sql('SELECT count(*) FROM statement_view')),
                   pending_events=int(sql('SELECT count(*) FROM outbox WHERE published_at IS NULL')))
            load_exit_codes.append(1)
            # Keep the original gate failed. Additional settling permits independent
            # recovery drills; it must never be counted as a passing 180-second gate.
            observed = wait_for(settled, timeout=600)
            record('additional_settling', case=case, observed_seconds=observed, original_gate_passed=False)
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
        rows_before_replay = sql('SELECT count(*) FROM postings')
        replay = transfer(accounts[0], accounts[1], key=key)
        assert replay['transactionId'] == original['transactionId'] and replay['idempotentReplay']
        assert rows_before_replay == sql('SELECT count(*) FROM postings')
        record('ledger_restart_replay', recovery_seconds=round(time.monotonic() - start, 3),
               original_transaction_returned=True, posting_count_unchanged=True)
        wait_for(settled)
        invariants()

        def committed_offsets():
            description = command(['docker', 'exec', '-e', 'KAFKA_HEAP_OPTS=-Xms64m -Xmx128m', 'ledgerline-kafka',
                '/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'kafka:9092',
                '--describe', '--group', 'ledgerline-projection'])
            rows = [line.split() for line in description.splitlines()
                    if line.lstrip().startswith('ledgerline-projection ') and 'ledger.transfers.v1' in line]
            assert len(rows) == 3, 'Expected all three topic partitions in consumer receipt'
            # A never-used partition has no committed offset ('-') and log end
            # zero; it has no pending records. Nonempty uncommitted partitions
            # must still block the caught-up observation.
            offsets = [0 if row[3] == '-' and row[4] == '0' else
                       int(row[3]) if row[3] != '-' else -1 for row in rows]
            if any(offset != int(row[4]) for offset, row in zip(offsets, rows)):
                return -1
            return sum(offsets)

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
        (destination / 'backup-fingerprints.json').write_text(json.dumps(
            {'source': before, 'restored': fingerprints(restore_db)}, indent=2))

        if options.get('retained_backup_sha256'):
            retained = Path('/tmp/retained-backup.dump')
            assert hashlib.sha256(retained.read_bytes()).hexdigest() == options['retained_backup_sha256']
            fixture_db = 'retained_source_' + uuid.uuid4().hex[:8]
            roundtrip_db = 'retained_roundtrip_' + uuid.uuid4().hex[:8]
            sql(f'CREATE DATABASE {fixture_db}', 'postgres')
            fixture_common = ['docker', 'run', '--rm', '--network', 'host', '-e', 'PGPASSWORD',
                '-e', 'PGSSLMODE', '-v', '/tmp/retained-backup.dump:/retained.dump:ro',
                '-v', f'{destination}:/evidence', 'postgres:16-alpine']
            command([*fixture_common, 'pg_restore', *db_args, '-d', fixture_db, '--no-owner',
                     '--no-privileges', '--exit-on-error', '/retained.dump'], env=db_env)
            assert int(sql('SELECT count(*) FROM postings', fixture_db)) == 103578
            retained_before = fingerprints(fixture_db)
            invariants(fixture_db)
            command([*common, 'pg_dump', *db_args, '-d', fixture_db, '-Fc',
                     '-f', '/evidence/retained-roundtrip.dump'], env=db_env)
            sql(f'CREATE DATABASE {roundtrip_db}', 'postgres')
            command([*common, 'pg_restore', *db_args, '-d', roundtrip_db, '--no-owner',
                     '--no-privileges', '--exit-on-error', '/evidence/retained-roundtrip.dump'], env=db_env)
            retained_after = fingerprints(roundtrip_db)
            assert retained_before == retained_after
            invariants(roundtrip_db)
            (destination / 'retained-fingerprints.json').write_text(json.dumps(
                {'source': retained_before, 'restored': retained_after}, indent=2))
            record('retained_backup_restore', postings=103578, matching_table_fingerprints=len(tables),
                   retained_backup_sha256=options['retained_backup_sha256'],
                   scope='Original retained backup restored on Azure, then independently dumped/restored; not the deleted historical live source')
    finally:
        command(['docker', 'start', 'ledgerline-kafka', 'ledgerline-ledger-service', 'ledgerline-projection-service'])

    wait_for(lambda: health(8080) and health(8081) and settled())
    final = invariants()
    (destination / 'final-invariants.txt').write_text(final)
    report['result'] = 'PASS' if all(code == 0 for code in load_exit_codes) else 'RECOVERY_PASS_LOAD_THRESHOLD_FAILED'
    (destination / 'report.json').write_text(json.dumps(report, indent=2))
    print(json.dumps({'result': report['result']}), flush=True)


if __name__ == '__main__':
    main()
