"""Alternating cold-start A/B runs on a disposable LedgerLine Azure VM.

Each case clones the same quiesced seed database. The original performance
workload, financial assertions, service CPU/memory allocations and 180-second
post-load catch-up deadline are retained. Never point this at an existing host.
"""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid


def command(args, **kwargs):
    return subprocess.check_output(args, text=True, timeout=600, **kwargs).strip()


def main():
    settings = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8-sig'))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request = urllib.request.Request(
        'http://169.254.169.254/metadata/instance/compute?api-version=2021-02-01',
        headers={'Metadata': 'true'})
    with opener.open(request, timeout=10) as response:
        group = json.load(response)['resourceGroupName']
    assert group == settings['resource_group'] and group.startswith('ledgerline-evidence-')
    destination = Path('/var/tmp/ledgerline-comparison')
    destination.mkdir(exist_ok=True)
    names = ['ledgerline-ledger-service', 'ledgerline-projection-service']
    containers = {name: json.loads(command(['docker', 'inspect', name]))[0] for name in names}
    environments = {name: dict(v.split('=', 1) for v in config['Config']['Env'] if '=' in v)
                    for name, config in containers.items()}
    ledger_env = environments[names[0]]
    url = urllib.parse.urlsplit(ledger_env['SPRING_DATASOURCE_URL'].removeprefix('jdbc:'))
    assert url.path == '/ledgerline'
    db_env = {**os.environ, 'PGPASSWORD': ledger_env['SPRING_DATASOURCE_PASSWORD'], 'PGSSLMODE': 'require'}
    db_args = ['-h', url.hostname, '-U', ledger_env['SPRING_DATASOURCE_USERNAME']]
    report = {'cases': [], 'order': ['baseline', 'optimized', 'optimized', 'baseline', 'baseline', 'optimized'],
              'method': 'fresh JVMs; identical cloned dataset; remove verified scratch DB after each case; original 150-second performance profile',
              'workload_sha256': hashlib.sha256(Path('/tmp/k6-cloud-load.js').read_bytes()).hexdigest(),
              'probe_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'allocations': {name: {'memory_bytes': c['HostConfig']['Memory'],
                                    'nano_cpus': c['HostConfig']['NanoCpus']} for name, c in containers.items()}}

    def save():
        (destination / 'report.json').write_text(json.dumps(report, indent=2))

    def sql(query, database='postgres'):
        return command(['psql', '-X', '-At', '-v', 'ON_ERROR_STOP=1', *db_args, '-d', database, '-c', query], env=db_env)

    def settled(database):
        return sql("SELECT (SELECT count(*) FROM postings)=(SELECT count(*) FROM statement_view) "
                   "AND NOT EXISTS(SELECT 1 FROM outbox WHERE published_at IS NULL)", database) == 't'

    def wait_for(predicate, timeout=180):
        start = time.monotonic()
        while time.monotonic() - start < timeout:
            if predicate():
                return round(time.monotonic() - start, 3)
            time.sleep(1)
        raise AssertionError('Cloud comparison observation exceeded its bounded deadline')

    def health(port):
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=3) as response:
                return json.load(response)['status'] == 'UP'
        except Exception:
            return False

    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b'=')

    data = encode({'alg': 'HS256', 'typ': 'JWT'}) + b'.' + encode(
        {'sub': 'comparison-operator', 'roles': ['OPERATOR'], 'exp': int(time.time()) + 7200})
    token = (data + b'.' + base64.urlsafe_b64encode(hmac.new(
        ledger_env['JWT_SECRET'].encode(), data, hashlib.sha256).digest()).rstrip(b'=')).decode()

    def api(path, body, key=None):
        headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
        if key:
            headers['Idempotency-Key'] = key
        request = urllib.request.Request('http://127.0.0.1:8080/api/v1/' + path,
            data=json.dumps(body).encode(), headers=headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            assert response.status in (200, 201)
            return json.load(response)

    # Equal starting balances/IDs/schema/history for every A/B case. No original
    # database is dropped, reset, or reused as a mutable benchmark case.
    funding = api('accounts', {'currency': 'USD', 'type': 'OVERDRAFT'})['id']
    accounts = [api('accounts', {'currency': 'USD', 'type': 'CUSTOMER'})['id'] for _ in range(2)]
    for account in accounts:
        api('transfers', {'fromAccountId': funding, 'toAccountId': account,
                         'amountMinor': 1000000, 'currency': 'USD'}, uuid.uuid4().hex)
    wait_for(lambda: settled('ledgerline'))
    command(['docker', 'stop', *names])
    wait_for(lambda: sql("SELECT count(*) FROM pg_stat_activity WHERE datname='ledgerline'") == '0')
    seed = 'ledgerline_ab_seed'
    sql(f'CREATE DATABASE {seed} WITH TEMPLATE ledgerline')
    tables = ['accounts', 'account_balances', 'transactions', 'postings', 'outbox', 'audit_log',
              'processed_events', 'statement_view', 'daily_account_summary',
              'flyway_schema_history', 'flyway_schema_history_projection']

    def fingerprint(database):
        values = {table: sql(f"SELECT count(*) || ':' || md5(coalesce(string_agg(row_to_json(t)::text,"
                            f"E'\\n' ORDER BY row_to_json(t)::text),'')) FROM {table} t", database)
                  for table in tables}
        return hashlib.sha256(json.dumps(values, sort_keys=True).encode()).hexdigest()

    report['seed_fingerprint'] = fingerprint(seed)
    images = {}
    for variant in ('baseline', 'optimized'):
        command(['docker', 'pull', settings[variant + '_image']])
        images[variant] = command(['docker', 'image', 'inspect', settings[variant + '_image'], '--format', '{{.Id}}'])
    report['images'] = images
    command(['docker', 'pull', 'grafana/k6:latest'])
    report['generator_image'] = command(['docker', 'image', 'inspect', 'grafana/k6:latest', '--format', '{{.Id}}'])
    save()

    for number, variant in enumerate(report['order'], 1):
        case = f'{number:02d}-{variant}'
        case_dir = destination / case
        case_dir.mkdir()
        command(['docker', 'rm', '-f', *names])
        database = 'ledgerline_ab_' + str(number)
        sql(f'CREATE DATABASE {database} WITH TEMPLATE {seed}')
        initial_fingerprint = fingerprint(database)
        assert initial_fingerprint == report['seed_fingerprint']
        topic = 'ledgerline.comparison.' + str(number)
        command(['docker', 'exec', '-e', 'KAFKA_HEAP_OPTS=-Xms64m -Xmx128m', 'ledgerline-kafka',
                 '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'kafka:9092', '--create',
                 '--topic', topic, '--partitions', '3', '--replication-factor', '1'])
        for index, name in enumerate(names):
            env = dict(environments[name])
            env['SPRING_DATASOURCE_URL'] = 'jdbc:' + url._replace(path='/' + database).geturl()
            env['LEDGER_KAFKA_TRANSFERS_TOPIC' if index == 0 else 'PROJECTION_KAFKA_TRANSFERS_TOPIC'] = topic
            if index == 1:
                env['SPRING_KAFKA_CONSUMER_GROUP_ID'] = 'comparison-' + str(number)
            env_path = Path('/tmp/comparison-' + str(index) + '.env')
            env_path.write_text('\n'.join(k + '=' + v for k, v in env.items()) + '\n')
            env_path.chmod(0o600)
            limits = containers[name]['HostConfig']
            image = images[variant] if index == 0 else containers[name]['Image']
            command(['docker', 'run', '-d', '--name', name, '--network', 'ledgerline',
                     '--memory', str(limits['Memory']), '--cpus', str(limits['NanoCpus'] / 1_000_000_000),
                     '-p', f'127.0.0.1:{8080 + index}:{8080 + index}', '--env-file', str(env_path), image])
        wait_for(lambda: health(8080) and health(8081), timeout=600)
        time.sleep(15)
        print(json.dumps({'case': case, 'phase': 'load-started'}), flush=True)
        load_env = {**os.environ, 'ACCESS_TOKEN': token, 'ACCOUNT_A': accounts[0], 'ACCOUNT_B': accounts[1],
                    'CLOUD_URL': 'http://127.0.0.1:8080/api/v1', 'K6_PROFILE': 'performance',
                    'K6_SUMMARY_TREND_STATS': 'avg,min,med,max,p(90),p(95),p(99)'}
        stop = threading.Event()

        def sample():
            with (case_dir / 'resources.jsonl').open('w') as stream:
                while not stop.is_set():
                    try:
                        output = command(['docker', 'stats', '--no-stream', '--format', '{{json .}}'])
                        stream.write(json.dumps({'utc_epoch': time.time(), 'docker_stats': output}) + '\n')
                        stream.flush()
                    except Exception as error:
                        stream.write(json.dumps({'observer_error': str(error)}) + '\n')
                    stop.wait(5)

        sampler = threading.Thread(target=sample, daemon=True)
        sampler.start()
        try:
            with (case_dir / 'load.log').open('w') as log:
                result = subprocess.run(['docker', 'run', '--rm', '--network', 'host', '--cpus=0.5',
                    '--user', f'{os.getuid()}:{os.getgid()}', '-e', 'ACCESS_TOKEN', '-e', 'ACCOUNT_A',
                    '-e', 'ACCOUNT_B', '-e', 'CLOUD_URL', '-e', 'K6_PROFILE', '-e', 'K6_SUMMARY_TREND_STATS',
                    '-v', '/tmp/k6-cloud-load.js:/bench/load.js:ro', '-v', f'{case_dir}:/evidence',
                    report['generator_image'], 'run', '/bench/load.js', '--summary-export=/evidence/summary.json'],
                    env=load_env, stdout=log, stderr=log, timeout=360)
        finally:
            stop.set()
            sampler.join(timeout=5)
        metrics = json.loads((case_dir / 'summary.json').read_text())['metrics']
        event = {'case': case, 'variant': variant, 'threshold_exit_code': result.returncode,
                 'initial_fingerprint': initial_fingerprint, 'transfers': metrics.get('cloud_transfers_success'),
                 'requests': metrics['http_reqs'], 'failed_requests': metrics['http_req_failed'],
                 'latency': metrics['http_req_duration'], 'catchup_seconds': None, 'invariants_passed': None}
        report['cases'].append(event)
        save()  # Preserve the measured load even if its subsequent validation fails.
        try:
            event['catchup_seconds'] = wait_for(lambda: settled(database))
            check = subprocess.run(['psql', '-X', '-v', 'ON_ERROR_STOP=1', *db_args, '-d', database,
                                    '-f', '/tmp/invariants.sql'], env=db_env, text=True,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
            (case_dir / 'invariants.txt').write_text(check.stdout)
            assert check.returncode == 0 and 'CLOUD_SQL_ASSERTIONS_PASSED' in check.stdout, 'Cloud SQL invariants failed'
            event['invariants_passed'] = True
        except Exception as error:
            event['invariants_passed'] = False
            event['validation_error'] = str(error)
            try:
                event['failure_state'] = json.loads(sql(
                    "SELECT json_build_object('postings',(SELECT count(*) FROM postings),"
                    "'statement_rows',(SELECT count(*) FROM statement_view),"
                    "'unpublished_outbox',(SELECT count(*) FROM outbox WHERE published_at IS NULL),"
                    "'processed_events',(SELECT count(*) FROM processed_events))", database))
            except Exception as observation_error:
                event['failure_observation_error'] = str(observation_error)
            report['result'] = 'VALIDATION_FAILED'
            save()
            raise
        save()
        print(json.dumps(event), flush=True)
        command(['docker', 'stop', *names])
        # Remove only this verified scratch clone. Earlier cases must not leave
        # background maintenance or dirty database pages competing with later cases.
        wait_for(lambda: sql("SELECT count(*) FROM pg_stat_activity WHERE datname='" + database + "'") == '0')
        sql(f'DROP DATABASE {database}')

    medians = {}
    for variant in ('baseline', 'optimized'):
        cases = [c for c in report['cases'] if c['variant'] == variant]
        medians[variant] = {'transfers_per_second': statistics.median(c['transfers']['rate'] for c in cases),
                            'p95_ms': statistics.median(c['latency']['p(95)'] for c in cases),
                            'p99_ms': statistics.median(c['latency']['p(99)'] for c in cases)}
    report['medians'] = medians
    report['result'] = 'PASS' if all(c['threshold_exit_code'] == 0 for c in report['cases']) else 'LOAD_THRESHOLD_FAILED'
    save()
    print(json.dumps({'result': report['result'], 'medians': medians}), flush=True)


if __name__ == '__main__':
    main()
