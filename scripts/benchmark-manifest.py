"""Freeze hashes for an explicitly built workspace; does not assert tests passed."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess

MODULES=('ledger-service','projection-service')
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def manifest(workspace,build_receipt=None):
    workspace=workspace.resolve()
    source={}
    for directory in ('ledger-service/src','projection-service/src','.mvn'):
        for path in (workspace/directory).rglob('*'):
            if path.is_file(): source[path.relative_to(workspace).as_posix()]=sha(path)
    for name in ('pom.xml','ledger-service/pom.xml','projection-service/pom.xml','mvnw','mvnw.cmd'):
        path=workspace/name
        if path.is_file(): source[name]=sha(path)
    artifacts={name:sha(workspace/name/'target'/(name+'-1.0.0-SNAPSHOT.jar')) for name in MODULES}
    provenance={'kind':'workspace snapshot','test_status':'UNVERIFIED'}
    if build_receipt:
        receipt=json.loads(build_receipt.read_text())
        assert Path(receipt['workspace']).resolve()==workspace,'Build receipt workspace mismatch'
        for name,expected in receipt['source_sha256'].items():
            assert sha(workspace/name)==expected,'Copied build source changed: '+name
        source=receipt['source_sha256']
        head=receipt.get('head','UNKNOWN')
        provenance={'kind':'copied build','identity_sha256':sha(build_receipt),'test_status':'UNVERIFIED'}
        summary_path=build_receipt.with_name('summary.json')
        if summary_path.exists():
            summary=json.loads(summary_path.read_text())
            provenance['summary_sha256']=sha(summary_path)
            provenance['test_status']='PASS' if summary and all(isinstance(stage,dict) and stage.get('status')=='PASS' for stage in summary.values()) else 'FAIL'
    else:
        result=subprocess.run(['git','-C',str(workspace),'rev-parse','HEAD'],capture_output=True,text=True)
        head=result.stdout.strip() if result.returncode==0 else 'UNKNOWN'
    return {'schema_version':1,'workspace':str(workspace),'source_head':head,'source_sha256':source,'artifact_sha256':artifacts,'created_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'build_provenance':provenance,'test_status':provenance['test_status'],'limitation':'Artifact hashes frozen now; independently retain build logs linking source to artifacts.'}
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--build-receipt',type=Path,help='Copied build identity.json; verifies every receipt source hash and links summary when present')
    args=parser.parse_args()
    if args.output.exists(): parser.error('Refuse to overwrite identity')
    args.output.write_text(json.dumps(manifest(args.workspace.resolve(),args.build_receipt),indent=2)+'\n',encoding='utf-8')
