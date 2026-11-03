"""Regression checks for benchmark identity matching and cleanup failure receipts."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

def module(name):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(name+'.py'))
    value=importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value
compare=module('benchmark-compare'); lab=module('benchmark-lab'); manifests=module('benchmark-manifest')

class EvidenceControlsTest(unittest.TestCase):
    def test_resource_mismatch_cannot_be_code_only_comparison(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); a=root/'baseline'; b=root/'candidate'; a.mkdir(); b.mkdir()
            for path,cpu in ((a,1),(b,2)):
                identity={key:'same' for key in compare.MATCH}; identity.update(source_manifest_sha256=path.name,budgets={'ledger_cpus':cpu})
                (path/'identity.json').write_text(json.dumps(identity))
                (path/'result.json').write_text('{"status":"FAIL"}')
                (path/'cleanup.json').write_text('{"status":"PASS"}')
                (path/'original-two-account-host.json').write_text(json.dumps({'docker_info':{'ID':'same'},'durability':'on/on/on'}))
            result=compare.compare(a,b)
            self.assertEqual('UNMATCHED',result['comparison_status'])
            self.assertEqual(['budgets'],result['mismatched_fields'])
            self.assertEqual('FAIL',result['baseline_result'])

    def test_cleanup_error_is_retained_in_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            value=lab.Lab.__new__(lab.Lab); value.project='llbench-test'; value.evidence=Path(directory)
            value.dc=lambda *args: (_ for _ in ()).throw(RuntimeError('synthetic daemon failure'))
            with patch.object(lab,'call',return_value=''):
                self.assertFalse(value.cleanup())
            receipt=json.loads((value.evidence/'cleanup.json').read_text())
            self.assertEqual('FAIL',receipt['status']); self.assertIn('synthetic daemon failure',receipt['errors'][0])

    def test_cgroup_v1_normalizes_nanoseconds(self):
        value=lab.Lab.__new__(lab.Lab)
        value.dc=lambda *args:'V1\nnr_periods 10\nnr_throttled 4\nthrottled_time 1500000\nCPU_USAGE_NS\n9000000\nMEMORY_CURRENT\n4096\nMEMORY_EVENTS\nfailcnt 0'
        result=value.cgroups()['postgres']
        self.assertEqual('PASS',result['status'])
        self.assertEqual(1500,result['value']['cpu']['throttled_usec'])
        self.assertEqual(9000,result['value']['cpu']['usage_usec'])

    def test_unknown_kafka_offset_is_not_zero(self):
        self.assertEqual([],lab.parse_lag('ledgerline-projection transfer-events 0 - 1 - consumer host client'))
        rows=lab.parse_lag('ledgerline-projection transfer-events 0 100 103 3 consumer host client')
        self.assertEqual(3,rows[0]['lag'])

    def test_jfr_analysis_distinguishes_zero_contention_and_cpu_samples(self):
        result=lab.analyze_jfr([{'type':'jdk.ExecutionSample','values':{'stackTrace':{'frames':[{'method':{'type':{'name':'example.Work'},'name':'run'}}]}}}])
        self.assertEqual(1,result['event_counts']['jdk.ExecutionSample'])
        self.assertEqual('example.Work.run',result['top_sampled_frames']['jdk.ExecutionSample'][0][0])
        self.assertNotIn('jdk.JavaMonitorEnter',result['event_counts'])

    def test_copied_build_receipt_without_git(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); workspace=root/'copy'; workspace.mkdir()
            source=workspace/'test-source.txt'; source.write_text('synthetic source')
            for name in manifests.MODULES:
                target=workspace/name/'target'; target.mkdir(parents=True)
                (target/(name+'-1.0.0-SNAPSHOT.jar')).write_text('synthetic jar')
            receipt=root/'identity.json'
            receipt.write_text(json.dumps({'workspace':str(workspace),'head':'synthetic-head','source_sha256':{'test-source.txt':manifests.sha(source)}}))
            (root/'summary.json').write_text('{"integration":{"status":"PASS"}}')
            frozen=manifests.manifest(workspace,receipt)
            self.assertEqual('synthetic-head',frozen['source_head'])
            self.assertEqual('PASS',frozen['build_provenance']['test_status'])
            source.write_text('changed')
            with self.assertRaisesRegex(AssertionError,'source changed'): manifests.manifest(workspace,receipt)

    def test_jfr_tmpfs_fallback_validates_chunks_and_streams_bytes(self):
        import base64
        commands=[]
        def execute(*args):
            commands.append(args)
            if args[0]=='jcmd': return 'Dump failed. Could not copy recording data.' if args[2]=='JFR.dump' else 'Stopped recording benchmark'
            if args[0]=='wc': return '0 /tmp/original-two-account.jfr'
            if args[0]=='find': return '/tmp/ll-jfr-original-two-account/chunks/one.jfr'
            if args[:2]==('jfr','summary'): return 'Version: 2.1\nChunks: 1'
            if args[0]=='sh': return ''
            if args[0]=='base64': return base64.b64encode(b'FLR\x00synthetic-complete-chunk').decode()
            raise AssertionError(args)
        data,diagnostic=lab.export_jfr(execute,'original-two-account')
        self.assertTrue(data.startswith(b'FLR\x00'))
        self.assertEqual('validated_repository_chunks_base64',diagnostic['method'])
        self.assertTrue(any(args[0]=='base64' for args in commands))
        self.assertFalse(any(args[0]=='docker' for args in commands))

if __name__=='__main__': unittest.main()
